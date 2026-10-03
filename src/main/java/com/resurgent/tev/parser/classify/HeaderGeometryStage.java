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
            COORD="text". A number is shown as # (a year such as 2025, and a running sequence
            such as 1, 2, 3, ... , are shown as themselves), an error as #ERR. A merged cell appears once, as its range, e.g. B4:C4="Title".
            Return one JSON object with keys:
              bands: array of A1 ranges, one per column-header band. A band is the run of rows
                whose text says what the columns below hold (titles, periods, units, multi-row
                headings) and the columns those headings govern, e.g. "B4:G7" for header rows
                4-7 over columns B to G. Include EVERY row of a multi-row heading. Never include
                data rows, or text inside the body that merely sits in a data column (a status
                word, a total label, a row's own name). If the region
                holds several tables, give one band per table, directly above that table's data.
                Empty array if the region has no column headings.
              rowLabelColumns: array of column letters whose cells name the row, left to right,
                e.g. ["A","B"]. Decide from the column's own heading and contents what its
                values ARE. Text that names an item or section is a label. Numbers that count
                time (years, months, periods, e.g. 1, 2, 3 under a heading about years or
                periods) are labels too: they say which period the row is. Numbers that merely
                index the rows (a serial or item number) are not, and neither are status or
                remarks columns to the right of the amounts. Empty array if rows carry no labels.
              annotationColumns: array of column letters holding text that qualifies a row's
                amounts rather than naming it (a status, remark, basis or note beside the
                numbers, e.g. whether the row is added, deducted or exempted). Empty if none.
              groupColumns: array of column letters where a filled cell starts a group that the
                rows beneath it belong to, until the next filled cell in that column: a running
                item number whose sub-rows leave it blank, or a section heading standing alone
                above its items. Empty if rows are not grouped.
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
        java.util.Set<Long> sequence = runningSequences(byRow);
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
                text.append(show(cell, sequence.contains(cell.cellId())));
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
        HeaderGeometry geometry = new HeaderGeometry(
                region.candidateId(),
                bands,
                withinRegion(root.path("rowLabelColumns"), region),
                withinRegion(root.path("annotationColumns"), region),
                withinRegion(root.path("groupColumns"), region));
        return geometry.isEmpty() ? Optional.empty() : Optional.of(geometry);
    }

    /**
     * Cells in a run of three or more whole numbers that each step up by one, across a row or down
     * a column (1, 2, 3, ...). Those are period or year labels, not amounts, so the grid shows them.
     */
    private static java.util.Set<Long> runningSequences(Map<Integer, List<InterpretationCellView>> byRow) {
        Map<Integer, Map<Integer, InterpretationCellView>> at = new HashMap<>();
        for (List<InterpretationCellView> row : byRow.values()) {
            for (InterpretationCellView cell : row) {
                if (wholeNumber(cell) != null) {
                    at.computeIfAbsent(cell.rowNum(), r -> new HashMap<>()).put(cell.colNum(), cell);
                }
            }
        }
        java.util.Set<Long> marked = new java.util.HashSet<>();
        for (Map.Entry<Integer, Map<Integer, InterpretationCellView>> row : at.entrySet()) {
            for (InterpretationCellView cell : row.getValue().values()) {
                markRun(cell, at, 0, 1, marked);
                markRun(cell, at, 1, 0, marked);
            }
        }
        return marked;
    }

    /** Marks the run that starts at {@code start} stepping by (dRow, dCol), if it has 3+ steps of +1. */
    private static void markRun(
            InterpretationCellView start, Map<Integer, Map<Integer, InterpretationCellView>> at,
            int dRow, int dCol, java.util.Set<Long> marked) {
        List<InterpretationCellView> run = new ArrayList<>(List.of(start));
        InterpretationCellView prev = start;
        while (true) {
            Map<Integer, InterpretationCellView> row = at.get(prev.rowNum() + dRow);
            InterpretationCellView next = row == null ? null : row.get(prev.colNum() + dCol);
            if (next == null || wholeNumber(next) != wholeNumber(prev) + 1) {
                break;
            }
            run.add(next);
            prev = next;
        }
        if (run.size() >= 3) {
            for (InterpretationCellView cell : run) {
                marked.add(cell.cellId());
            }
        }
    }

    private static Long wholeNumber(InterpretationCellView c) {
        if (!"number".equals(c.valueType()) || c.numericValue() == null || c.numericValue().isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(c.numericValue());
            return value == Math.rint(value) && Math.abs(value) < 1e9 ? (long) value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Integer> withinRegion(JsonNode letters, CandidateRow region) {
        return HeaderGeometry.columnList(letters).stream()
                .filter(col -> col >= region.bboxMinCol() && col <= region.bboxMaxCol())
                .toList();
    }

    private static boolean inside(CandidateRow r, InterpretationCellView c) {
        return c.rowNum() >= r.bboxMinRow() && c.rowNum() <= r.bboxMaxRow()
                && c.colNum() >= r.bboxMinCol() && c.colNum() <= r.bboxMaxCol();
    }

    /** Empty cells, and merged participants, which only repeat their anchor. */
    private static boolean skipped(InterpretationCellView c) {
        return c.isMergedParticipant() || "empty".equals(c.valueType()) && !c.isMergedAnchor();
    }

    private static String show(InterpretationCellView c, boolean inSequence) {
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
                if (value == Math.rint(value) && (inSequence || value >= 1900 && value <= 2100)) {
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
