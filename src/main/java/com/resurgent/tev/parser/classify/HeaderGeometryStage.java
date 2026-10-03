package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Asks the LLM, once per region, where that region keeps its labels. The question is about
 * structure, not cells: the answer is a handful of coordinates, and code builds every cell's
 * label text from them ({@link HeaderGeometry}). Cost scales with regions, not cells.
 */
final class HeaderGeometryStage {

    static final String SYSTEM = """
            You read one region of a financial-model worksheet and say where its labels are.
            The user message shows the region as a grid: one line per row, each cell as
            COORD="text". A number is shown as # (a year such as 2025 is shown as itself),
            an error as #ERR. A merged cell appears once, as its range, e.g. B4:C4="Title".
            Return one JSON object with keys:
              bands: array of A1 ranges, one per column-header band. A band is the run of rows
                whose text says what the columns below hold (titles, periods, units, multi-row
                headings) and the columns those headings govern, e.g. "B4:G7" for header rows
                4-7 over columns B to G. Include EVERY row of a multi-row heading. Never include
                data rows or body text such as "Nil", "Total" or a row's own label. If the region
                holds several tables, give one band per table, directly above that table's data.
                Empty array if the region has no column headings.
              rowLabelColumns: array of column letters that hold the name of each row (item
                names, section labels), left to right, e.g. ["A","B"]. Leave out serial-number
                columns (Sl. No., 1, 2, 3) and status or remarks columns to the right of the
                numbers. Empty array if rows carry no labels.
            Use only coordinates that appear in the grid. JSON only, no markdown fences.
            """;

    /** One region to ask about, with the sheet's cells (only those inside the region are shown). */
    record Region(CandidateRow candidate, String sheetName, List<InterpretationCellView> cells) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ROWS = 400;
    private static final int MAX_COLS = 60;
    private static final int MAX_BAND_ROWS = 12;
    private static final int MAX_TEXT = 60;

    private HeaderGeometryStage() {}

    /** One call per region; a region whose call fails or answers nothing usable is simply absent. */
    static Map<Long, HeaderGeometry> ask(List<Region> regions, ClassifierLlm llm, int concurrency) {
        List<Callable<Optional<HeaderGeometry>>> tasks = new ArrayList<>();
        for (Region region : regions) {
            tasks.add(() -> parse(
                    llm.classifyCellJson(SYSTEM, render(region.candidate(), region.sheetName(), region.cells()), 1024),
                    region.candidate()));
        }
        List<ParallelCalls.Outcome<Optional<HeaderGeometry>>> outcomes = ParallelCalls.run(tasks, concurrency);
        Map<Long, HeaderGeometry> found = new HashMap<>();
        int failed = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            ParallelCalls.Outcome<Optional<HeaderGeometry>> outcome = outcomes.get(i);
            if (!outcome.ok()) {
                failed++;
                continue;
            }
            outcome.value().ifPresent(g -> found.put(g.candidateId(), g));
        }
        LlmStats.GLOBAL.add("header-geometry", "regions_asked", regions.size());
        LlmStats.GLOBAL.add("header-geometry", "regions_with_geometry", found.size());
        LlmStats.GLOBAL.add("header-geometry", "regions_failed", failed);
        return found;
    }

    static String render(CandidateRow region, String sheetName, List<InterpretationCellView> cells) {
        Map<Integer, List<InterpretationCellView>> byRow = new java.util.TreeMap<>();
        for (InterpretationCellView cell : cells) {
            if (cell.worksheetId() != region.worksheetId() || !inside(region, cell) || skipped(cell)) {
                continue;
            }
            byRow.computeIfAbsent(cell.rowNum(), r -> new ArrayList<>()).add(cell);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("region ").append(region.candidateId()).append(" on sheet \"").append(sheetName).append("\", ")
                .append(bounds(region)).append('\n');
        int rows = 0;
        for (Map.Entry<Integer, List<InterpretationCellView>> row : byRow.entrySet()) {
            if (++rows > MAX_ROWS) {
                sb.append("... (rows below ").append(row.getKey() - 1).append(" not shown)\n");
                break;
            }
            List<InterpretationCellView> line = row.getValue();
            line.sort(java.util.Comparator.comparingInt(InterpretationCellView::colNum));
            StringBuilder text = new StringBuilder();
            for (InterpretationCellView cell : line) {
                if (cell.colNum() - region.bboxMinCol() >= MAX_COLS) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append(" | ");
                }
                text.append(show(cell));
            }
            if (text.length() > 0) {
                sb.append(row.getKey()).append(": ").append(text).append('\n');
            }
        }
        return sb.toString();
    }

    /** The geometry in the model's answer, or empty when it is unreadable or names nothing in range. */
    static Optional<HeaderGeometry> parse(String completion, CandidateRow region) {
        if (completion == null || completion.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(LayerAResponseParser.extractJsonObject(completion));
        } catch (Exception e) {
            return Optional.empty();
        }
        List<HeaderGeometry.Band> bands = new ArrayList<>();
        for (JsonNode node : root.path("bands")) {
            HeaderGeometry.Band band = HeaderGeometry.parseBand(node.asText());
            if (band != null
                    && band.rowMin() >= region.bboxMinRow() && band.rowMax() <= region.bboxMaxRow()
                    && band.colMin() >= region.bboxMinCol() && band.colMax() <= region.bboxMaxCol()
                    && band.rowMax() - band.rowMin() + 1 <= MAX_BAND_ROWS) {
                bands.add(band);
            }
        }
        List<Integer> labelColumns = new ArrayList<>();
        for (JsonNode node : root.path("rowLabelColumns")) {
            int col = HeaderGeometry.columnNumber(node.asText());
            if (col >= region.bboxMinCol() && col <= region.bboxMaxCol() && !labelColumns.contains(col)) {
                labelColumns.add(col);
            }
        }
        labelColumns.sort(Integer::compare);
        HeaderGeometry geometry = new HeaderGeometry(region.candidateId(), bands, labelColumns);
        return geometry.isEmpty() ? Optional.empty() : Optional.of(geometry);
    }

    private static boolean inside(CandidateRow r, InterpretationCellView c) {
        return c.rowNum() >= r.bboxMinRow() && c.rowNum() <= r.bboxMaxRow()
                && c.colNum() >= r.bboxMinCol() && c.colNum() <= r.bboxMaxCol();
    }

    /** Empty cells, and merged participants, which only repeat their anchor. */
    private static boolean skipped(InterpretationCellView c) {
        return c.isMergedParticipant() || "empty".equals(c.valueType()) && !c.isMergedAnchor();
    }

    private static String show(InterpretationCellView c) {
        String at = c.isMergedAnchor() && c.mergedRange() != null ? c.mergedRange() : c.coord();
        if (c.isError() || "error".equals(c.valueType())) {
            return at + "=#ERR";
        }
        String text = InterpretationEvidenceResolver.labelText(c);
        if (text != null) {
            return at + "=\"" + (text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "..." : text) + "\"";
        }
        if (c.dateValue() != null && !c.dateValue().isBlank()) {
            return at + "=" + c.dateValue();
        }
        String numeric = c.numericValue();
        if (numeric != null && !numeric.isBlank()) {
            try {
                double value = Double.parseDouble(numeric);
                if (value == Math.rint(value) && value >= 1900 && value <= 2100) {
                    return at + "=" + (int) value;
                }
            } catch (NumberFormatException ignored) {
                // not a year; shown as an amount
            }
        }
        return at + "=#";
    }

    private static String bounds(CandidateRow r) {
        return "rows " + r.bboxMinRow() + "-" + r.bboxMaxRow() + ", columns "
                + HeaderGeometry.columnLetters(r.bboxMinCol()) + "-" + HeaderGeometry.columnLetters(r.bboxMaxCol());
    }
}
