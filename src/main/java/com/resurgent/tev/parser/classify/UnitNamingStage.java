package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Names the unit of measurement of quantities, counts and rates. The decision model settles a cell's
 * kind and scale and cannot name a unit, and the label vocabulary knows a handful of them (sqft, sqm,
 * nos, keys, rooms), so most such cells ended with no unit at all (367 of 461 quantity cells in one
 * workbook) and an input's wrong unit was passed down to every cell derived from it. A unit belongs to
 * a label, not to a cell, so the model is asked once per distinct label, with the cells' region, labels
 * and a few values, and its answer is checked against the text it was shown.
 */
final class UnitNamingStage {

    /** Where the unit came from: the labels say it, or the model worked it out from the subject. */
    static final String STATED = "stated";
    static final String INFERRED = "inferred";

    static final int GROUPS_PER_CALL = 30;
    private static final int MAX_UNIT_CHARS = 24;

    static final String SYSTEM = """
            You name the unit of measurement of numbers in a financial-model worksheet.
            Each group is one row label and column header; its cells all have one kind:
              quantity: an amount measured in a physical unit (MWh, kg, tonnes, sqm, days, hours, trains...)
              count: a number of discrete things (nos, rooms, guests, trains, employees...)
              rate: an amount per unit; the unit is what it is per (a rate of Rs per sqm is "sqm")
            For each group answer {"id": n, "unit": "...", "basis": "stated" | "inferred" | "none", "evidence": "..."}.
              stated: the labels, region description or sheet name give the unit; evidence is the exact words
                copied from them. A figure per period is written unit/period, for example "kWh/day".
              inferred: nothing names the unit but the subject makes it clear (the generation of a power plant is
                in kWh or MWh); evidence is the words that led you there.
              none: the unit cannot be told. Use it, with an empty unit, whenever you are not sure.
            A currency or a scale (rupees, lakh, crore, thousand, million) is not a unit of measurement, and a
            percentage is not a unit for a quantity; never answer with one. Use the conventional symbol (kWh, MW,
            kg, sqm, sqft, nos, days, months, hrs). Return one JSON object {"groups":[...]} with one entry per group,
            nothing else.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> NOT_A_UNIT = Set.of(
            "rs", "inr", "rupee", "rupees", "usd", "lakh", "lakhs", "lac", "lacs", "crore", "crores", "cr", "thousand",
            "million", "billion", "mn", "bn", "%", "percent", "percentage", "unit", "units", "none", "n/a", "na");

    private UnitNamingStage() {}

    /** The unit given to a cell, how it is known, and the words it rests on. */
    record Note(String unit, String basis, String evidence) {}

    private static final class Group {
        final String key;
        final String sheet;
        final String rowLabel;
        final String columnLabel;
        final String partOf;
        final String kind;
        final RegionContext region;
        final List<InterpretationCellView> cells = new ArrayList<>();
        final Set<String> currentUnits = new LinkedHashSet<>();

        Group(String key, String sheet, String rowLabel, String columnLabel, String partOf, String kind,
                RegionContext region) {
            this.key = key;
            this.sheet = sheet;
            this.rowLabel = rowLabel;
            this.columnLabel = columnLabel;
            this.partOf = partOf;
            this.kind = kind;
            this.region = region;
        }
    }

    /**
     * Name units for the quantity, count and rate cells that have none or only an inherited one, change
     * {@code settled} accordingly and return a note per cell that was named. A model that is missing or
     * fails leaves the cells as they were.
     */
    static Map<Long, Note> run(
            List<InterpretationCellView> cells,
            Map<Long, ReadingOutcome> settled,
            CellContext ctx,
            ClassifierLlm llm,
            DynamicKindTokens dictionary,
            int concurrency) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (InterpretationCellView cell : cells) {
            ReadingOutcome outcome = settled.get(cell.cellId());
            if (outcome == null || !outcome.typed() || !measured(outcome.kind)) {
                continue;
            }
            boolean inputWithUnit = ReadingOutcome.INPUT.equals(outcome.typeSource) && !outcome.unit.isBlank();
            if (inputWithUnit) {
                continue; // the cell's own labels gave it a unit
            }
            String row = ctx.rowLabel(cell);
            String column = ctx.columnLabel(cell);
            String sheet = ctx.sheetName(cell);
            String key = sheet + "|" + KindTokens.normalizeLabel(row) + "|" + KindTokens.normalizeLabel(column)
                    + "|" + outcome.kind;
            Group group = groups.computeIfAbsent(key, k -> new Group(
                    k, sheet, row, column, ctx.partOf(cell), outcome.kind, ctx.region(cell)));
            group.cells.add(cell);
            if (!outcome.unit.isBlank()) {
                group.currentUnits.add(outcome.unit);
            }
        }
        Map<Long, Note> notes = new LinkedHashMap<>();
        if (groups.isEmpty() || llm == null) {
            return notes;
        }
        List<Group> all = new ArrayList<>(groups.values());
        List<List<Group>> batches = new ArrayList<>();
        for (int i = 0; i < all.size(); i += GROUPS_PER_CALL) {
            batches.add(all.subList(i, Math.min(all.size(), i + GROUPS_PER_CALL)));
        }
        List<Callable<List<Answer>>> tasks = new ArrayList<>();
        for (List<Group> batch : batches) {
            tasks.add(() -> parse(llm.classifyCellJson(SYSTEM, prompt(batch, settled), 4096), batch.size()));
        }
        List<ParallelCalls.Outcome<List<Answer>>> outcomes = ParallelCalls.run(tasks, Math.max(1, concurrency));
        int failedBatches = 0;
        String firstFailure = null;
        int named = 0;
        int stated = 0;
        int inferred = 0;
        int groupsNamed = 0;
        for (int b = 0; b < batches.size(); b++) {
            if (!outcomes.get(b).ok()) {
                failedBatches++;
                if (firstFailure == null) {
                    firstFailure = String.valueOf(outcomes.get(b).error());
                }
                continue;
            }
            for (Answer answer : outcomes.get(b).value()) {
                if (answer.id < 1 || answer.id > batches.get(b).size()) {
                    continue;
                }
                Group group = batches.get(b).get(answer.id - 1);
                Note note = check(answer, group);
                if (note == null) {
                    continue;
                }
                groupsNamed++;
                for (InterpretationCellView cell : group.cells) {
                    ReadingOutcome outcome = settled.get(cell.cellId());
                    if (outcome == null || (ReadingOutcome.INPUT.equals(outcome.typeSource) && !outcome.unit.isBlank())) {
                        continue;
                    }
                    settled.put(cell.cellId(), outcome.withUnit(note.unit()));
                    notes.put(cell.cellId(), note);
                    named++;
                    if (STATED.equals(note.basis())) {
                        stated++;
                    } else {
                        inferred++;
                    }
                }
                learn(group, note, ctx, dictionary);
            }
        }
        LlmStats.GLOBAL.add("layer-b", "unit_groups_asked", all.size());
        LlmStats.GLOBAL.add("layer-b", "unit_groups_named", groupsNamed);
        LlmStats.GLOBAL.add("layer-b", "unit_batches_failed", failedBatches);
        LlmStats.GLOBAL.add("layer-b", "unit_cells_named", named);
        LlmStats.GLOBAL.add("layer-b", "unit_cells_named_stated", stated);
        LlmStats.GLOBAL.add("layer-b", "unit_cells_named_inferred", inferred);
        System.err.println("[units] asked about " + all.size() + " labels in " + batches.size() + " calls ("
                + failedBatches + " failed): named " + groupsNamed + " labels, " + named + " cells ("
                + stated + " stated in the labels, " + inferred + " inferred)"
                + (firstFailure == null ? "" : "; first failure: " + firstFailure));
        System.err.flush();
        return notes;
    }

    private static boolean measured(String kind) {
        return ReadingOutcome.QUANTITY.equals(kind) || ReadingOutcome.COUNT.equals(kind)
                || ReadingOutcome.RATE.equals(kind);
    }

    private static String prompt(List<Group> batch, Map<Long, ReadingOutcome> settled) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode list = root.putArray("groups");
        for (int i = 0; i < batch.size(); i++) {
            Group g = batch.get(i);
            ObjectNode node = list.addObject();
            node.put("id", i + 1);
            node.put("kind", g.kind);
            node.put("sheet", g.sheet);
            node.put("row_label", g.rowLabel);
            node.put("column_header", g.columnLabel);
            if (g.partOf != null && !g.partOf.isBlank()) {
                node.put("part_of", g.partOf);
            }
            if (g.region.known()) {
                node.put("region", g.region.about());
            }
            if (!g.currentUnits.isEmpty()) {
                node.put("unit_now_recorded_unconfirmed", String.join(", ", g.currentUnits));
            }
            node.put("cells", g.cells.size());
            ArrayNode samples = node.putArray("sample_cells");
            for (InterpretationCellView cell : g.cells.subList(0, Math.min(3, g.cells.size()))) {
                String shown = cell.displayValue() != null ? cell.displayValue() : cell.numericValue();
                String formula = cell.formulaText();
                samples.add(cell.coord() + " = " + (shown == null ? "" : shortened(shown, 14))
                        + (formula != null && !formula.isBlank() ? " (" + shortened(formula, 50) + ")" : ""));
            }
        }
        return "Name the unit for each group.\n" + root;
    }

    private static String shortened(String text, int max) {
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private record Answer(int id, String unit, String basis, String evidence) {}

    static List<Answer> parse(String json, int expected) {
        List<Answer> answers = new ArrayList<>();
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("unit naming returned nothing");
        }
        try {
            JsonNode root = MAPPER.readTree(LayerAResponseParser.extractJsonObject(json));
            JsonNode list = root.isArray() ? root : root.path("groups");
            for (JsonNode node : list) {
                answers.add(new Answer(
                        node.path("id").asInt(-1),
                        node.path("unit").asText("").trim(),
                        node.path("basis").asText("none").trim().toLowerCase(Locale.ROOT),
                        node.path("evidence").asText("").trim()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("unit naming reply is not usable: " + e.getMessage(), e);
        }
        return answers;
    }

    /**
     * The note for an answer, or null when it names nothing usable. "Stated" has to be shown: its evidence must
     * be words that appear in the text the model was given, else it is only an inference.
     */
    private static Note check(Answer answer, Group group) {
        String unit = answer.unit;
        if (unit.isBlank() || unit.length() > MAX_UNIT_CHARS || "none".equals(answer.basis)
                || NOT_A_UNIT.contains(unit.toLowerCase(Locale.ROOT))
                || (!STATED.equals(answer.basis) && !INFERRED.equals(answer.basis))) {
            return null;
        }
        String haystack = plain(group.sheet + " " + group.rowLabel + " " + group.columnLabel + " "
                + group.partOf + " " + group.region.about() + " " + group.region.packetHead());
        String evidence = plain(answer.evidence);
        boolean shown = !evidence.isBlank() && haystack.contains(evidence);
        String basis = STATED.equals(answer.basis) && shown ? STATED : INFERRED;
        return new Note(unit, basis, answer.evidence);
    }

    private static String plain(String text) {
        if (text == null) {
            return "";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return s.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static void learn(Group group, Note note, CellContext ctx, DynamicKindTokens dictionary) {
        if (dictionary == null || !group.region.known() || !group.region.learnable()) {
            return;
        }
        InterpretationCellView first = group.cells.get(0);
        dictionary.observe(group.region.scheduleFamily(), group.rowLabel, group.kind, note.unit(),
                ctx.workbookKey(), group.region.sheetName(), first.rowNum());
    }
}
