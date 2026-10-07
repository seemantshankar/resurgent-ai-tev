package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * A scale for the money nothing states, judged from what the cell is called and how the rest of the
 * workbook's numbers sit. A business whose revenue is stated in crores does not have an operating
 * cost of 0.74 rupees, and it does not pay one employee 100,000 crores either: the first needs the
 * workbook's magnitudes, the second needs the labels, so a model reads both. The model is shown each
 * group's labels and values, the scales the workbook states and how big its money typically is, and
 * answers with a scale and a reason. Its answer is kept only when the scale is one the workbook states
 * (or plain units for an amount of one item) and the amount it implies is a possible one.
 *
 * <p>This is an inference and is recorded as one ({@code scale_basis = inferred}, with the reason).
 * It runs after the arithmetic links have said what they can, and with no model nothing changes.
 */
final class ScaleInference {

    /** Scales used by fewer money cells than this are not the workbook's convention. */
    static final int MIN_ANCHORS_PER_SCALE = 5;
    /** A sheet's own stated scales are preferred when it has at least this many cells that state one. */
    static final int MIN_SHEET_ANCHORS = 3;
    static final int GROUPS_PER_CALL = 25;
    static final double MIN_CONFIDENCE = 0.8;
    /** A group's typical amount, once scaled, must be at least a unit and less than this (1e12: a lakh crore). */
    static final double MIN_IMPLIED_AMOUNT = 1d;
    static final double MAX_IMPLIED_AMOUNT = 5e11;
    /**
     * One line cannot be thousands of times bigger than the workbook's typical money: an employee's
     * salary read as 100,000 crores is 1e12 against a workbook whose money sits near 1e8.
     */
    static final double MAX_ORDERS_ABOVE_TYPICAL = 4d;

    static final String SYSTEM = """
            You decide the scale of money amounts in a financial-model worksheet whose scale nothing states.
            Scales: unit (plain currency units: rupees, dollars), thousand, lakh (100,000), million,
            crore (10,000,000), billion.
            Each group is cells under one row label and column header. You are told the group's values and
            what the rest of the workbook states: the scales it uses and how large its money typically is once
            its scale is applied. Choose the scale that makes these amounts possible in the real world.
              - Totals, costs, revenues and balances of a business usually follow the workbook's stated scale.
              - The amount of one item (a salary per person, a price or tariff per unit, rent per month, a fee,
                a deposit, a rate per sqm) is usually in plain currency units even when the totals are in crores:
                100,000 crores for one employee's salary is impossible, 100,000 rupees is normal.
              - A figure such as 0.74 for the yearly operating cost of a plant whose revenue is stated in crores
                is crores, not rupees.
            Do not choose a scale only because the workbook uses it: the labels decide what kind of amount it is.
            Answer {"groups":[{"id":n,"scale":"unit|thousand|lakh|million|crore|billion|unknown","confidence":0.0-1.0,
            "reason":"..."}]}, one entry per group, nothing else. Use "unknown" whenever the labels and values do
            not make one scale clearly more plausible than the others.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScaleInference() {}

    /** The scale chosen for one cell, and why. */
    record Inference(CellScale scale, String evidence) {}

    /** The cells of one arithmetic class that still have no scale. */
    record Group(List<InterpretationCellView> cells) {}

    /** What the workbook's stated money says about a group: its candidate scales and the typical amount. */
    record Evidence(Map<CellScale, Integer> scales, double reference, String basis, double typicalLog10) {}

    static Map<Long, Inference> apply(
            List<InterpretationCellView> cells,
            Map<Long, ReadingOutcome> settled,
            UnstatedScales unknowns,
            Map<Long, String> sheetNames,
            CellContext ctx,
            ClassifierLlm llm,
            int concurrency) {
        Map<Long, Inference> chosen = new LinkedHashMap<>();
        Anchors anchors = Anchors.of(cells, settled, sheetNames);
        List<Group> groups = unstatedGroups(cells, settled, unknowns);
        if (groups.isEmpty()) {
            return chosen;
        }
        List<Group> asked = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        for (Group group : groups) {
            Evidence e = evidenceFor(group, anchors, sheetNames);
            if (e != null) {
                asked.add(group);
                evidence.add(e);
            }
        }
        int decided = 0;
        int cellsScaled = 0;
        int failedBatches = 0;
        int rejected = 0;
        String firstFailure = null;
        if (llm != null && !asked.isEmpty()) {
            List<Callable<List<Answer>>> tasks = new ArrayList<>();
            List<int[]> ranges = new ArrayList<>();
            for (int from = 0; from < asked.size(); from += GROUPS_PER_CALL) {
                int to = Math.min(asked.size(), from + GROUPS_PER_CALL);
                ranges.add(new int[] {from, to});
                // The labels come from a cache that is not safe to share between threads: every prompt is
                // built here, and only the model calls run in parallel.
                String prompt = prompt(asked.subList(from, to), evidence.subList(from, to), anchors, ctx, sheetNames);
                tasks.add(() -> parse(llm.classifyCellJson(SYSTEM, prompt, 4096)));
            }
            List<ParallelCalls.Outcome<List<Answer>>> outcomes = ParallelCalls.run(tasks, Math.max(1, concurrency));
            for (int b = 0; b < ranges.size(); b++) {
                if (!outcomes.get(b).ok()) {
                    failedBatches++;
                    if (firstFailure == null) {
                        firstFailure = String.valueOf(outcomes.get(b).error());
                    }
                    continue;
                }
                int from = ranges.get(b)[0];
                for (Answer answer : outcomes.get(b).value()) {
                    int index = from + answer.id - 1;
                    if (answer.id < 1 || index >= ranges.get(b)[1]) {
                        continue;
                    }
                    Group group = asked.get(index);
                    Inference inference = check(answer, group, evidence.get(index));
                    if (inference == null) {
                        if (answer.scale != null) {
                            rejected++;
                        }
                        continue;
                    }
                    decided++;
                    for (InterpretationCellView cell : group.cells()) {
                        ReadingOutcome outcome = settled.get(cell.cellId());
                        settled.put(cell.cellId(), outcome.withScale(inference.scale(), true));
                        chosen.put(cell.cellId(), inference);
                        cellsScaled++;
                    }
                }
            }
        }
        LlmStats.GLOBAL.add("layer-b", "scale_groups_unstated", groups.size());
        LlmStats.GLOBAL.add("layer-b", "scale_groups_asked", asked.size());
        LlmStats.GLOBAL.add("layer-b", "scale_groups_inferred", decided);
        LlmStats.GLOBAL.add("layer-b", "scale_cells_inferred", cellsScaled);
        System.err.println("[scale-inference] " + groups.size() + " groups of money with no stated scale; asked about "
                + asked.size() + " (" + failedBatches + " calls failed): " + decided + " took the scale the labels and the "
                + "workbook's numbers point to (" + cellsScaled + " cells), " + rejected + " answers rejected as implausible, "
                + (groups.size() - decided) + " stay unknown"
                + (firstFailure == null ? "" : "; first failure: " + firstFailure));
        System.err.flush();
        return chosen;
    }

    /** Cells still unstated whose arithmetic class nothing constrains, grouped by class. */
    static List<Group> unstatedGroups(
            List<InterpretationCellView> cells, Map<Long, ReadingOutcome> settled, UnstatedScales unknowns) {
        Map<Integer, List<InterpretationCellView>> classes = new LinkedHashMap<>();
        for (InterpretationCellView cell : cells) {
            ReadingOutcome outcome = settled.get(cell.cellId());
            if (outcome == null || !outcome.scaleUnstated() || !unknowns.required(outcome.scaleUnknown).isEmpty()) {
                continue; // stated, or read in conflicting scales: not a question of what is plausible
            }
            classes.computeIfAbsent(unknowns.root(outcome.scaleUnknown), r -> new ArrayList<>()).add(cell);
        }
        List<Group> groups = new ArrayList<>();
        classes.values().forEach(cs -> groups.add(new Group(cs)));
        return groups;
    }

    /** The scales the group's sheets state (or the workbook, when they state none) and the typical amount. */
    static Evidence evidenceFor(Group group, Anchors anchors, Map<Long, String> sheetNames) {
        List<Double> logs = new ArrayList<>();
        Set<String> sheets = new LinkedHashSet<>();
        for (InterpretationCellView cell : group.cells()) {
            double v = Math.abs(number(cell));
            if (v > 0) {
                logs.add(Math.log10(v));
            }
            sheets.add(sheetNames.getOrDefault(cell.worksheetId(), ""));
        }
        if (logs.isEmpty() || anchors.all.isEmpty()) {
            return null; // all zero, or nothing in the workbook states a scale to compare with
        }
        Map<CellScale, Integer> scales = new EnumMap<>(CellScale.class);
        List<Double> reference = new ArrayList<>();
        for (String sheet : sheets) {
            Anchors.Sheet s = anchors.bySheet.get(sheet);
            if (s != null && s.count >= MIN_SHEET_ANCHORS) {
                s.scales.forEach((scale, n) -> scales.merge(scale, n, Integer::sum));
                reference.addAll(s.logs);
            }
        }
        String basis = "the money on " + String.join(", ", sheets) + " that states a scale";
        if (scales.isEmpty()) {
            anchors.scales.forEach((scale, n) -> {
                if (n >= MIN_ANCHORS_PER_SCALE && n >= 0.02 * anchors.all.size()) {
                    scales.put(scale, n);
                }
            });
            reference = new ArrayList<>(anchors.all);
            basis = "the workbook's money that states a scale";
        }
        if (scales.isEmpty()) {
            return null;
        }
        return new Evidence(scales, median(reference), basis, median(logs));
    }

    /** The reply's answer for one group, or null when it is unknown, unsure, not allowed or impossible. */
    private static Inference check(Answer answer, Group group, Evidence evidence) {
        CellScale scale = scaleOf(answer.scale);
        if (scale == null || answer.confidence < MIN_CONFIDENCE || answer.reason.isBlank()) {
            return null;
        }
        // Only a scale the workbook states, or plain units (the amount of one item), may be chosen.
        if (scale != CellScale.UNIT && !evidence.scales().containsKey(scale)) {
            return null;
        }
        double implied = Math.pow(10, evidence.typicalLog10() + Math.log10(scale.multiplier()));
        if (implied < MIN_IMPLIED_AMOUNT || implied > MAX_IMPLIED_AMOUNT
                || Math.log10(implied) > evidence.reference() + MAX_ORDERS_ABOVE_TYPICAL) {
            return null; // paise, or far bigger than anything else in the workbook: not a possible amount
        }
        String text = String.format(Locale.ROOT, "%s (typical value %.3g read as %s is %.3g; %s state %s)",
                answer.reason, Math.pow(10, evidence.typicalLog10()), scale.wireName(), implied,
                evidence.basis(), scalesText(evidence.scales()));
        return new Inference(scale, text);
    }

    private static CellScale scaleOf(String text) {
        if (text == null) {
            return null;
        }
        switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "unit": return CellScale.UNIT;
            case "thousand": return CellScale.THOUSAND;
            case "lakh": return CellScale.LAKH;
            case "million": return CellScale.MILLION;
            case "crore": return CellScale.CRORE;
            case "billion": return CellScale.BILLION;
            default: return null; // "unknown" and anything else
        }
    }

    private static String scalesText(Map<CellScale, Integer> scales) {
        List<String> parts = new ArrayList<>();
        scales.forEach((scale, n) -> parts.add(scale.wireName() + " x" + n));
        return String.join(", ", parts);
    }

    private static String prompt(
            List<Group> batch, List<Evidence> evidence, Anchors anchors, CellContext ctx, Map<Long, String> sheetNames) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode list = root.putArray("groups");
        for (int i = 0; i < batch.size(); i++) {
            Group g = batch.get(i);
            Evidence e = evidence.get(i);
            ObjectNode node = list.addObject();
            node.put("id", i + 1);
            Set<String> sheets = new LinkedHashSet<>();
            Set<String> rows = new LinkedHashSet<>();
            Set<String> columns = new LinkedHashSet<>();
            for (InterpretationCellView cell : g.cells()) {
                sheets.add(sheetNames.getOrDefault(cell.worksheetId(), ""));
                if (rows.size() < 4) {
                    rows.add(ctx.rowLabel(cell));
                }
                if (columns.size() < 3) {
                    columns.add(ctx.columnLabel(cell));
                }
            }
            node.put("sheet", String.join(", ", sheets));
            node.put("row_labels", String.join(" | ", rows));
            node.put("column_headers", String.join(" | ", columns));
            InterpretationCellView first = g.cells().get(0);
            RegionContext region = ctx.region(first);
            if (region.known()) {
                node.put("region", region.about());
            }
            String partOf = ctx.partOf(first);
            if (partOf != null && !partOf.isBlank()) {
                node.put("part_of", partOf);
            }
            node.put("cells", g.cells().size());
            node.put("typical_value", String.format(Locale.ROOT, "%.4g", Math.pow(10, e.typicalLog10())));
            ArrayNode samples = node.putArray("sample_cells");
            for (InterpretationCellView cell : g.cells().subList(0, Math.min(3, g.cells().size()))) {
                String formula = cell.formulaText();
                samples.add(cell.coord() + " = " + trimmed(cell.displayValue() != null ? cell.displayValue() : cell.numericValue(), 14)
                        + (formula != null && !formula.isBlank() ? " (" + trimmed(formula, 50) + ")" : ""));
            }
            ObjectNode context = node.putObject("what_the_workbook_states");
            context.put("scales_stated_by", e.basis() + ": " + scalesText(e.scales()));
            context.put("typical_amount_of_that_money", String.format(Locale.ROOT, "%.3g", Math.pow(10, e.reference())));
            ObjectNode implied = node.putObject("typical_value_would_be");
            for (CellScale scale : e.scales().keySet()) {
                implied.put(scale.wireName(), String.format(Locale.ROOT, "%.3g",
                        Math.pow(10, e.typicalLog10() + Math.log10(scale.multiplier()))));
            }
            implied.put("unit", String.format(Locale.ROOT, "%.3g", Math.pow(10, e.typicalLog10())));
        }
        return "Decide the scale of each group.\n" + root;
    }

    private static String trimmed(String text, int max) {
        if (text == null) {
            return "";
        }
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private record Answer(int id, String scale, double confidence, String reason) {}

    static List<Answer> parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("scale inference returned nothing");
        }
        try {
            JsonNode root = MAPPER.readTree(LayerAResponseParser.extractJsonObject(json));
            JsonNode list = root.isArray() ? root : root.path("groups");
            List<Answer> answers = new ArrayList<>();
            for (JsonNode node : list) {
                answers.add(new Answer(
                        node.path("id").asInt(-1),
                        node.path("scale").asText(""),
                        node.path("confidence").asDouble(0.0),
                        node.path("reason").asText("").trim()));
            }
            return answers;
        } catch (Exception e) {
            throw new IllegalStateException("scale inference reply is not usable: " + e.getMessage(), e);
        }
    }

    static double number(InterpretationCellView cell) {
        try {
            return Double.parseDouble(cell.numericValue());
        } catch (RuntimeException e) {
            return 0d;
        }
    }

    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2d;
    }

    /** The money whose scale is stated: how big it is once its scale is applied, overall and per sheet. */
    static final class Anchors {
        static final class Sheet {
            int count;
            final Map<CellScale, Integer> scales = new EnumMap<>(CellScale.class);
            final List<Double> logs = new ArrayList<>();
        }

        final List<Double> all = new ArrayList<>();
        final Map<CellScale, Integer> scales = new EnumMap<>(CellScale.class);
        final Map<String, Sheet> bySheet = new HashMap<>();

        static Anchors of(
                List<InterpretationCellView> cells, Map<Long, ReadingOutcome> settled, Map<Long, String> sheetNames) {
            Anchors anchors = new Anchors();
            for (InterpretationCellView cell : cells) {
                ReadingOutcome outcome = settled.get(cell.cellId());
                if (outcome == null || !outcome.typed() || !ReadingOutcome.MONEY.equals(outcome.kind)
                        || outcome.scale == null || outcome.scaleInferred) {
                    continue; // only scales the workbook states, not ones worked out from other scales
                }
                double v = Math.abs(number(cell));
                if (v <= 0) {
                    continue;
                }
                double implied = Math.log10(v) + Math.log10(outcome.scale.multiplier());
                anchors.all.add(implied);
                anchors.scales.merge(outcome.scale, 1, Integer::sum);
                Sheet sheet = anchors.bySheet.computeIfAbsent(
                        sheetNames.getOrDefault(cell.worksheetId(), ""), k -> new Sheet());
                sheet.count++;
                sheet.scales.merge(outcome.scale, 1, Integer::sum);
                sheet.logs.add(implied);
            }
            return anchors;
        }
    }
}
