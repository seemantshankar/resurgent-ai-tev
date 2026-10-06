package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * A second look at the regions region layout called scratch. Only Layer A looks again at a
 * region called main or helper, so a scratch call from region layout was final, and it varies
 * from run to run: one run threw away two CMA Form III blocks of about 300 cells each. A
 * decision model gives an independent opinion from a summary of each region; the region stays
 * scratch only when that opinion confirms it, and goes to Layer A otherwise.
 */
final class RegionTriageStage {

    /** A scratch or orphan opinion at or above this confidence confirms region layout's scratch call. */
    static final double CONFIRMS_SCRATCH_AT = 0.7;

    /**
     * A scratch region with no numbers is only a few labels or notes ("ok", "Loan to be updated",
     * a unit note); Layer A is not asked about it unless it holds this many text cells, so a real
     * block of labels or a title band still gets a look.
     */
    static final int TEXT_BLOCK_CELLS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CELL_REF = Pattern.compile("\\$?[A-Z]{1,3}\\$?\\d+");

    private RegionTriageStage() {}

    /** One region to summarise, with the sheet's cells (the summary uses those inside it and its labels). */
    record Region(CandidateRow candidate, String sheetName, List<InterpretationCellView> cells) {}

    /** The state sent to the decision model, and the counts the escalation rule reads. */
    record Summary(String stateJson, int numericCells, int textCells) {}

    /** Whether a scratch region is sent to Layer A, and the opinion behind that. */
    record Verdict(RegionTriageClient.Opinion opinion, boolean escalated) {}

    static boolean escalates(String structuralRole, Summary summary, RegionTriageClient.Opinion opinion) {
        if (!"scratch".equals(structuralRole)) {
            return false;
        }
        if (summary.numericCells() == 0 && summary.textCells() < TEXT_BLOCK_CELLS) {
            return false;
        }
        boolean confirmed = opinion != null
                && ("scratch".equals(opinion.choice()) || "orphan".equals(opinion.choice()))
                && opinion.confidence() >= CONFIRMS_SCRATCH_AT;
        return !confirmed;
    }

    /**
     * Summarise every region and, when a client is given, ask it about each; a call that fails
     * leaves the region without an opinion, which sends a scratch region on to Layer A.
     */
    static Map<Long, Verdict> run(List<Region> regions, RegionTriageClient client, int concurrency) {
        Map<Long, Summary> summaries = new LinkedHashMap<>();
        Map<Long, SheetIndex> sheets = new HashMap<>();
        for (Region region : regions) {
            SheetIndex index = sheets.computeIfAbsent(
                    region.candidate().worksheetId(), id -> new SheetIndex(region.cells()));
            summaries.put(region.candidate().candidateId(), summarize(region, index));
        }
        Map<Long, RegionTriageClient.Opinion> opinions = new HashMap<>();
        int failed = 0;
        if (client != null) {
            List<Region> asked = new ArrayList<>(regions);
            List<Callable<RegionTriageClient.Opinion>> tasks = new ArrayList<>();
            for (Region region : asked) {
                String state = summaries.get(region.candidate().candidateId()).stateJson();
                tasks.add(() -> client.decide(state));
            }
            List<ParallelCalls.Outcome<RegionTriageClient.Opinion>> outcomes = ParallelCalls.run(tasks, concurrency);
            for (int i = 0; i < outcomes.size(); i++) {
                if (outcomes.get(i).ok()) {
                    opinions.put(asked.get(i).candidate().candidateId(), outcomes.get(i).value());
                } else {
                    failed++;
                }
            }
        }
        Map<Long, Verdict> verdicts = new LinkedHashMap<>();
        int scratch = 0;
        int confirmed = 0;
        int tiny = 0;
        int escalated = 0;
        for (Region region : regions) {
            long id = region.candidate().candidateId();
            Summary summary = summaries.get(id);
            RegionTriageClient.Opinion opinion = opinions.get(id);
            boolean escalate = escalates(region.candidate().structuralRole(), summary, opinion);
            verdicts.put(id, new Verdict(opinion, escalate));
            if ("scratch".equals(region.candidate().structuralRole())) {
                scratch++;
                if (escalate) {
                    escalated++;
                } else if (summary.numericCells() == 0 && summary.textCells() < TEXT_BLOCK_CELLS) {
                    tiny++;
                } else {
                    confirmed++;
                }
            }
        }
        LlmStats.GLOBAL.add("layer-a", "region_triage_asked", client == null ? 0 : regions.size());
        LlmStats.GLOBAL.add("layer-a", "region_triage_failed", failed);
        LlmStats.GLOBAL.add("layer-a", "region_triage_scratch_regions", scratch);
        LlmStats.GLOBAL.add("layer-a", "region_triage_scratch_confirmed", confirmed);
        LlmStats.GLOBAL.add("layer-a", "region_triage_scratch_sent_to_layer_a", escalated);
        System.err.println("[region-triage] " + (client == null ? "no decision model configured" : client.model()
                + " asked about " + regions.size() + " regions (" + failed + " failed)")
                + "; region layout called " + scratch + " scratch: " + confirmed + " confirmed, " + tiny
                + " left (only a few labels or notes), " + escalated + " sent to Layer A");
        System.err.flush();
        return verdicts;
    }

    /** The facts the decision model reads about one region. */
    static Summary summarize(Region region, SheetIndex sheet) {
        CandidateRow r = region.candidate();
        List<InterpretationCellView> nums = new ArrayList<>();
        List<InterpretationCellView> texts = new ArrayList<>();
        int errors = 0;
        for (InterpretationCellView cell : sheet.within(r)) {
            switch (cell.valueType() == null ? "" : cell.valueType()) {
                case "number" -> nums.add(cell);
                case "text" -> texts.add(cell);
                case "error" -> errors++;
                default -> { }
            }
        }
        int labelled = 0;
        List<String> labels = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (InterpretationCellView cell : nums) {
            String label = sheet.labelLeft(cell);
            if (label != null) {
                labelled++;
                if (labels.size() < 14 && seen.add(label)) {
                    labels.add(shorten(label, 48));
                }
            }
        }
        for (InterpretationCellView cell : texts) {
            String text = cell.textValue();
            if (text != null && labels.size() < 14 && seen.add(text)) {
                labels.add(shorten(text, 48));
            }
        }
        List<String> headers = new ArrayList<>();
        Set<String> seenHeaders = new HashSet<>();
        for (InterpretationCellView cell : nums.subList(0, Math.min(nums.size(), 400))) {
            String header = sheet.headerAbove(cell);
            if (header != null && headers.size() < 6 && seenHeaders.add(header)) {
                headers.add(shorten(header, 30));
            }
        }
        Map<String, Integer> shapes = new LinkedHashMap<>();
        int formulas = 0;
        int otherSheet = 0;
        for (InterpretationCellView cell : nums) {
            String formula = cell.formulaText();
            if (formula == null || formula.isBlank()) {
                continue;
            }
            formulas++;
            if (formula.contains("!")) {
                otherSheet++;
            }
            String shape = CELL_REF.matcher(formula.replaceFirst("^[=+]+", "")).replaceAll("ref");
            shapes.merge(shorten(shape, 60), 1, Integer::sum);
        }
        int values = 0;
        int nearZero = 0;
        for (InterpretationCellView cell : nums) {
            try {
                double v = Double.parseDouble(cell.numericValue());
                values++;
                if (Math.abs(v) < 1e-3) {
                    nearZero++;
                }
            } catch (RuntimeException ignored) {
                // a number cell without a parseable value is not counted
            }
        }
        ObjectNode state = MAPPER.createObjectNode();
        state.put("sheet", region.sheetName());
        state.put("region", HeaderGeometry.columnLetters(r.bboxMinCol()) + r.bboxMinRow() + ":"
                + HeaderGeometry.columnLetters(r.bboxMaxCol()) + r.bboxMaxRow());
        state.put("numeric_cells", nums.size());
        state.put("formula_cells", formulas);
        state.put("text_cells", texts.size());
        state.put("error_cells", errors);
        state.put("formulas_reading_other_sheets", otherSheet);
        state.put("numeric_cells_with_a_text_label_to_their_left", labelled + " of " + nums.size());
        state.put("numeric_cells_near_zero", nearZero + " of " + values);
        labels.forEach(state.putArray("row_labels_and_text")::add);
        headers.forEach(state.putArray("column_headers_above")::add);
        ArrayNode common = state.putArray("common_formula_shapes");
        shapes.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(4)
                .forEach(e -> common.add(e.getValue() + "x " + e.getKey()));
        ArrayNode sample = state.putArray("sample_numeric_cells");
        int step = Math.max(1, nums.size() / 6);
        for (int i = 0; i < nums.size() && sample.size() < 6; i += step) {
            InterpretationCellView cell = nums.get(i);
            String shown = shorten(cell.displayValue() != null ? cell.displayValue() : cell.numericValue(), 14);
            String formula = cell.formulaText();
            sample.add(cell.coord() + "=" + shown
                    + (formula != null && !formula.isBlank() ? " (" + shorten(formula, 40) + ")" : ""));
        }
        return new Summary(state.toString(), nums.size(), texts.size());
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    /** A worksheet's cells by position, for the label and header lookups. */
    static final class SheetIndex {
        private final Map<Integer, TreeMap<Integer, InterpretationCellView>> byRow = new HashMap<>();

        SheetIndex(List<InterpretationCellView> cells) {
            for (InterpretationCellView cell : cells) {
                String type = cell.valueType();
                if ("number".equals(type) || "text".equals(type) || "error".equals(type) || "date".equals(type)) {
                    byRow.computeIfAbsent(cell.rowNum(), r -> new TreeMap<>()).put(cell.colNum(), cell);
                }
            }
        }

        List<InterpretationCellView> within(CandidateRow r) {
            List<InterpretationCellView> out = new ArrayList<>();
            for (int row = r.bboxMinRow(); row <= r.bboxMaxRow(); row++) {
                TreeMap<Integer, InterpretationCellView> cells = byRow.get(row);
                if (cells != null) {
                    out.addAll(cells.subMap(r.bboxMinCol(), true, r.bboxMaxCol(), true).values());
                }
            }
            return out;
        }

        /** The nearest text to the cell's left on its row. */
        String labelLeft(InterpretationCellView cell) {
            TreeMap<Integer, InterpretationCellView> row = byRow.get(cell.rowNum());
            if (row == null) {
                return null;
            }
            for (InterpretationCellView left : row.headMap(cell.colNum(), false).descendingMap().values()) {
                if ("text".equals(left.valueType()) && left.textValue() != null && !left.textValue().isBlank()) {
                    return left.textValue();
                }
            }
            return null;
        }

        /** The nearest text or date above the cell in the same column, up to five rows up. */
        String headerAbove(InterpretationCellView cell) {
            for (int row = cell.rowNum() - 1; row > Math.max(0, cell.rowNum() - 6); row--) {
                TreeMap<Integer, InterpretationCellView> cells = byRow.get(row);
                InterpretationCellView above = cells == null ? null : cells.get(cell.colNum());
                if (above != null && ("text".equals(above.valueType()) || "date".equals(above.valueType()))) {
                    String text = above.textValue() != null ? above.textValue() : above.displayValue();
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }
            return null;
        }
    }
}
