package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * LLM-based fallback for cell type classification.
 *
 * Used after structural inference (total row rules, column consensus) to classify
 * remaining UNTYPABLE cells. This provides a last-resort classification for cells
 * that could not be deterministically typed through labels, format, formulas, or
 * structural context.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Only classifies cells that remain UNTYPABLE after deterministic rules
 *   <li>Returns type + confidence, not just type
 *   <li>Respects the "nothing incorrect is written" principle — low-confidence results stay untyped
 *   <li>Batches requests for efficiency
 *   <li>Integrates with existing {@link ClassifierLlm} infrastructure
 * </ul>
 *
 * @see ReadingOutcome
 * @see CellReadingInferencer
 * @see ClassifierLlm
 */
public class CellTypeClassifierLlm {
    private static final double MIN_CONFIDENCE = 0.80;
    /** Measured on OM Arham: D1 agreed with the chat model on 99.8% of cells at >= 0.90, 97% at >= 0.80. */
    static final double DEFAULT_DECISION_MIN_CONFIDENCE = 0.90;
    static final int DEFAULT_DECISION_CONCURRENCY = 8;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM_PROMPT = """
            You are a financial model cell type classifier. Your task is to classify numeric cells in financial models.

            For each cell, determine:
            1. kind: one of {money, quantity, rate, percent, count, ratio}
            2. scale: one of {unit, thousand, lakh, million, crore, billion}
            3. unit: optional unit of measurement (e.g., "sqm", "kg", "" for none)
            4. currency: optional 3-letter currency code (e.g., "INR", "USD", "" for none)
            5. confidence: 0.0-1.0 representing your confidence in this classification

            Return a JSON object with these fields.
            Each request may begin with a "Region" line describing the schedule the cells sit in
            (its family and what it is about). Use it to decide the kind of a label that carries no
            unit or currency word: a row such as "Firefighting & Misc." inside an expenses schedule
            is money even though the label never says so.

            Respect the "nothing incorrect is written" principle: if unsure, return lower confidence.
            """;

    private final WorkspaceRepository repo;
    private final ClassifierLlm llm;
    private final DynamicKindTokens dynamicDict;
    private CellContext ctx = CellContext.scan(List.of());
    private int batchSize = 15;
    private int concurrency = 1;
    private java.util.Set<Long> llmWorksheetIds; // null: every sheet is sent to the model
    private UnstatedScales unknowns = new UnstatedScales();
    private CellDecisionClient decisions; // null: every cell goes to the chat model
    private double decisionMinConfidence = DEFAULT_DECISION_MIN_CONFIDENCE;
    private int decisionConcurrency = DEFAULT_DECISION_CONCURRENCY;
    private boolean compareDecisions;     // shadow mode: ask both models, trust only the chat model
    private java.nio.file.Path compareCsv;
    private Map<Long, Set<Long>> precedentIds = Map.of(); // formula cell -> the cells its formula reads
    private Map<Long, InterpretationCellView> cellsById = Map.of();
    private boolean stateSheet = true;       // facts sent to the decision model beyond the region block
    private boolean stateStatedScale = true;
    private boolean stateInputs = true;
    private final Set<Long> decisionAsked = new java.util.HashSet<>(); // cells the decision model has been asked about
    private final Set<Long> chatAsked = new java.util.HashSet<>();     // cells the chat model has been asked about
    private final List<InterpretationCellView> comparedCells = new ArrayList<>();
    private final List<ParallelCalls.Outcome<CellDecisionClient.Decision>> comparedOutcomes = new ArrayList<>();

    public CellTypeClassifierLlm(WorkspaceRepository repo, ClassifierLlm llm) {
        this(repo, llm, new DynamicKindTokens());
    }

    CellTypeClassifierLlm(WorkspaceRepository repo, ClassifierLlm llm, DynamicKindTokens dynamicDict) {
        this.repo = repo;
        this.llm = llm;
        this.dynamicDict = dynamicDict;
    }

    /** Cells per call and calls in flight at once. */
    CellTypeClassifierLlm withBatching(int batchSize, int concurrency) {
        this.batchSize = Math.max(1, batchSize);
        this.concurrency = Math.max(1, concurrency);
        return this;
    }

    /** Send only cells on these worksheets to the model; others keep their deterministic result. */
    CellTypeClassifierLlm withWorksheetScope(java.util.Set<Long> worksheetIds) {
        this.llmWorksheetIds = worksheetIds;
        return this;
    }

    /** Where money typed from a keyword alone, with no scale stated anywhere, gets its unknown scale. */
    CellTypeClassifierLlm withUnstatedScales(UnstatedScales unknowns) {
        this.unknowns = unknowns;
        return this;
    }

    /**
     * Try a decision model (kind + scale from fixed options) on every cell before the chat
     * model; cells it is unsure about, or fails on, still go to the chat model.
     */
    CellTypeClassifierLlm withDecisionModel(CellDecisionClient decisions) {
        this.decisions = decisions;
        return this;
    }

    /**
     * What each formula cell reads, so the decision model is told the kinds of those cells instead of
     * having to follow {@code 'P  L '!D29/12*$D$12} itself.
     */
    CellTypeClassifierLlm withPrecedents(
            Map<Long, Set<Long>> precedents, Map<Long, InterpretationCellView> cellsById) {
        this.precedentIds = precedents;
        this.cellsById = cellsById;
        return this;
    }

    /** Which facts the decision state carries besides the cell and its labels; all on by default. */
    CellTypeClassifierLlm withDecisionStateFacts(boolean sheet, boolean statedScale, boolean inputs) {
        this.stateSheet = sheet;
        this.stateStatedScale = statedScale;
        this.stateInputs = inputs;
        return this;
    }

    /** Confidence the decision model must reach to settle a cell, and calls in flight at once. */
    CellTypeClassifierLlm withDecisionTuning(double minConfidence, int concurrency) {
        this.decisionMinConfidence = minConfidence;
        this.decisionConcurrency = Math.max(1, concurrency);
        return this;
    }

    /**
     * Shadow mode: the decision model is asked about every cell the chat model will type, but
     * its answers are never applied. A report of how often the two agree is printed afterwards
     * (and written to {@code csv} when given).
     */
    CellTypeClassifierLlm withDecisionComparison(java.nio.file.Path csv) {
        this.compareDecisions = true;
        this.compareCsv = csv;
        return this;
    }

    /**
     * Classify remaining UNTYPABLE cells using LLM.
     * Modifies the settled map in-place, replacing UNTYPABLE outcomes with LLM-inferred ones.
     *
     * @param cells all interpretation cells for the parse run
     * @param settled map of cellId → ReadingOutcome, modified in-place
     */
    public void classifyRemaining(List<InterpretationCellView> cells, Map<Long, ReadingOutcome> settled) {
        classifyRemaining(cells, settled, CellContext.scan(cells));
    }

    /**
     * As {@link #classifyRemaining(List, Map)}, with labels and Layer A region supplied by
     * {@code context} (the candidate-scoped resolver in production).
     */
    void classifyRemaining(
            List<InterpretationCellView> cells, Map<Long, ReadingOutcome> settled, CellContext context) {
        this.ctx = context;
        System.err.println("[cell-classifier] Starting classifyRemaining, total cells=" + cells.size());
        System.err.flush();
        List<InterpretationCellView> unclassified = untypable(cells, settled);
        if (unclassified.isEmpty()) {
            System.err.println("[cell-classifier] No unclassified cells found, skipping LLM");
            System.err.flush();
            return;
        }

        List<InterpretationCellView> stillUntyped = typeByDictionary(unclassified, settled);
        if (stillUntyped.isEmpty()) {
            System.err.println("[cell-classifier] All cells typed by dictionary, skipping LLM");
            System.err.flush();
            return;
        }

        stillUntyped = typeByDecisionModel(stillUntyped, cells, settled);
        if (stillUntyped.isEmpty()) {
            System.err.println("[cell-classifier] All cells typed by decision model, skipping chat LLM");
            System.err.flush();
            finishComparison(settled);
            return;
        }
        typeByChat(stillUntyped, cells, settled);
        finishComparison(settled);
    }

    // ---- the stages of classifyRemaining, callable one at a time ---------------------------
    //
    // A caller that can type more cells between stages (formulas follow the cells typed before
    // them) drives these itself; classifyRemaining runs them back to back. A cell the decision
    // model or the chat model has been asked about is not asked again by the same classifier.

    /** Use this context for the labels, regions and stated scales of the stages that follow. */
    void useContext(CellContext context) {
        this.ctx = context;
    }

    /** Whether the models may be asked about this cell (it is on a sheet in scope). */
    boolean inScope(InterpretationCellView cell) {
        return llmWorksheetIds == null || llmWorksheetIds.contains(cell.worksheetId());
    }

    /** The numeric, in-scope cells still marked untypable: what the stages below may settle. */
    List<InterpretationCellView> untypable(List<InterpretationCellView> cells, Map<Long, ReadingOutcome> settled) {
        List<InterpretationCellView> untyped = new ArrayList<>();
        for (InterpretationCellView cell : cells) {
            ReadingOutcome outcome = settled.get(cell.cellId());
            if (outcome != null && ReadingOutcome.UNTYPABLE.equals(outcome.refusal)
                    && isNumeric(cell)
                    && (llmWorksheetIds == null || llmWorksheetIds.contains(cell.worksheetId()))) {
                untyped.add(cell);
            }
        }
        return untyped;
    }

    /** Static and learned dictionary, by the cell's labels. Returns the cells it left untyped. */
    List<InterpretationCellView> typeByDictionary(
            List<InterpretationCellView> unclassified, Map<Long, ReadingOutcome> settled) {
        List<InterpretationCellView> stillUntyped = new ArrayList<>();
        for (InterpretationCellView cell : unclassified) {
            ReadingOutcome outcome = tryDictionaryBasedTyping(cell);
            if (outcome != null) {
                settled.put(cell.cellId(), outcome);
            } else {
                stillUntyped.add(cell);
            }
        }
        int typedByDictionary = unclassified.size() - stillUntyped.size();
        LlmStats.GLOBAL.add("layer-b", "cells_untypable_before_classifier", unclassified.size());
        LlmStats.GLOBAL.add("layer-b", "cells_typed_dictionary", typedByDictionary);
        if (typedByDictionary > 0) {
            System.err.println("[cell-classifier] Typed " + typedByDictionary + " cells via KindTokens dictionary");
        }
        return stillUntyped;
    }

    /**
     * The decision model, when one is configured. Returns the cells it did not settle, in their
     * original order: every cell when there is no decision model, and in shadow mode (where it is
     * asked but its answers are never applied).
     */
    List<InterpretationCellView> typeByDecisionModel(
            List<InterpretationCellView> pending,
            List<InterpretationCellView> allCells,
            Map<Long, ReadingOutcome> settled) {
        if (decisions == null) {
            return pending;
        }
        List<InterpretationCellView> toAsk = new ArrayList<>();
        for (InterpretationCellView cell : pending) {
            if (decisionAsked.add(cell.cellId())) {
                toAsk.add(cell);
            }
        }
        if (compareDecisions) {
            if (!toAsk.isEmpty()) {
                long shadowStart = System.nanoTime();
                comparedCells.addAll(toAsk);
                comparedOutcomes.addAll(askDecisions(toAsk, allCells, settled));
                LlmStats.GLOBAL.add("layer-b", "cells_decision_shadow", toAsk.size());
                System.err.println("[cell-decision] shadow mode: asked D1 about " + toAsk.size() + " cells in "
                        + (System.nanoTime() - shadowStart) / 1_000_000 + "ms; answers are NOT applied");
                System.err.flush();
            }
            return pending;
        }
        List<InterpretationCellView> deferredAsked = decideWithDecisionModel(toAsk, allCells, settled);
        Set<Long> unsettled = new java.util.HashSet<>();
        deferredAsked.forEach(c -> unsettled.add(c.cellId()));
        List<InterpretationCellView> deferred = new ArrayList<>();
        for (InterpretationCellView cell : pending) {
            // Asked before and still untyped, or asked now and not settled.
            ReadingOutcome now = settled.get(cell.cellId());
            if (unsettled.contains(cell.cellId())
                    || (now != null && ReadingOutcome.UNTYPABLE.equals(now.refusal))) {
                deferred.add(cell);
            }
        }
        return deferred;
    }

    /** The chat model, in batches of one region's cells at a time. */
    void typeByChat(
            List<InterpretationCellView> pending,
            List<InterpretationCellView> cells,
            Map<Long, ReadingOutcome> settled) {
        List<InterpretationCellView> stillUntyped = new ArrayList<>();
        for (InterpretationCellView cell : pending) {
            if (chatAsked.add(cell.cellId())) {
                stillUntyped.add(cell);
            }
        }
        if (stillUntyped.isEmpty()) {
            return;
        }

        LlmStats.GLOBAL.add("layer-b", "cells_to_chat", stillUntyped.size());
        System.err.println("[cell-classifier] Classifying " + stillUntyped.size() + " untyped cells via LLM");
        System.err.flush();

        // Keep cells of one region together (stable order), then fill batches across region
        // boundaries: a call costs about the same for 1 cell or 15, so small regions must not
        // each get their own. Each region's description precedes its cells in the prompt.
        Map<String, List<InterpretationCellView>> byRegion = new java.util.LinkedHashMap<>();
        for (InterpretationCellView cell : stillUntyped) {
            RegionContext region = ctx.region(cell);
            String groupKey = region.known() ? "c" + region.candidateId() : "w" + cell.worksheetId();
            byRegion.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(cell);
        }
        List<InterpretationCellView> ordered = new ArrayList<>(stillUntyped.size());
        byRegion.values().forEach(ordered::addAll);
        List<List<InterpretationCellView>> batches = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i += batchSize) {
            batches.add(ordered.subList(i, Math.min(i + batchSize, ordered.size())));
        }
        System.err.println("[cell-classifier] " + batches.size() + " batches of up to " + batchSize
                + " cells, " + concurrency + " at a time");
        System.err.flush();

        // A wave of batches goes out together; their answers are applied in order before the
        // next wave is built, so later prompts still see earlier answers as neighbour hints.
        int cellsDone = 0;
        for (int start = 0; start < batches.size(); start += concurrency) {
            List<List<InterpretationCellView>> wave =
                    batches.subList(start, Math.min(start + concurrency, batches.size()));
            List<List<CellTypeRequest>> requestsByBatch = new ArrayList<>();
            List<List<RegionContext>> regionsByBatch = new ArrayList<>();
            List<java.util.concurrent.Callable<List<CellTypeResponse>>> tasks = new ArrayList<>();
            for (List<InterpretationCellView> batch : wave) {
                List<CellTypeRequest> requests = new ArrayList<>();
                List<RegionContext> regions = new ArrayList<>();
                for (InterpretationCellView cell : batch) {
                    requests.add(buildCellTypeRequest(cell, cells, settled));
                    regions.add(ctx.region(cell));
                }
                requestsByBatch.add(requests);
                regionsByBatch.add(regions);
                tasks.add(() -> classifyBatchCells(requests, regions));
            }
            List<ParallelCalls.Outcome<List<CellTypeResponse>>> outcomes = ParallelCalls.run(tasks, concurrency);
            for (int i = 0; i < wave.size(); i++) {
                applyBatchOutcome(wave.get(i), requestsByBatch.get(i), regionsByBatch.get(i), outcomes.get(i), settled);
                cellsDone += wave.get(i).size();
            }
            int batchesDone = Math.min(start + concurrency, batches.size());
            System.err.println("[cell-classifier] batches " + batchesDone + "/" + batches.size()
                    + " done (" + cellsDone + "/" + ordered.size() + " cells)");
            System.err.flush();
        }
    }

    /** Shadow mode only: report, and write the CSV for, every cell the decision model was asked about. */
    void finishComparison(Map<Long, ReadingOutcome> settled) {
        if (comparedCells.isEmpty()) {
            return;
        }
        List<InterpretationCellView> compared = List.copyOf(comparedCells);
        List<ParallelCalls.Outcome<CellDecisionClient.Decision>> shadow = List.copyOf(comparedOutcomes);
        comparedCells.clear();
        comparedOutcomes.clear();
        reportComparison(compared, shadow, settled);
    }

    private void reportComparison(
            List<InterpretationCellView> compared,
            List<ParallelCalls.Outcome<CellDecisionClient.Decision>> shadow,
            Map<Long, ReadingOutcome> settled) {
        List<DecisionComparison.Row> rows = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < compared.size(); i++) {
            if (!shadow.get(i).ok()) {
                failed++;
                continue;
            }
            InterpretationCellView cell = compared.get(i);
            ReadingOutcome chat = settled.get(cell.cellId());
            boolean typed = chat != null && chat.refusal == null && chat.kind != null;
            rows.add(new DecisionComparison.Row(
                    cell.cellId(), cell.coord(), ctx.rowLabel(cell), ctx.columnLabel(cell), shadow.get(i).value(),
                    typed ? chat.kind : null,
                    typed && chat.scale != null ? chat.scale.wireName() : null,
                    ctx.sheetName(cell), hasRegionBlock(ctx.region(cell)),
                    ctx.statedScale(cell) == null ? "" : ctx.statedScale(cell).wireName()));
        }
        LlmStats.GLOBAL.add("layer-b", "cells_decision_failed", failed);
        DecisionComparison comparison = new DecisionComparison(rows);
        System.err.print(comparison.report());
        if (failed > 0) {
            System.err.println("[d1-compare] " + failed + " D1 calls failed and are not in the comparison");
        }
        if (compareCsv != null) {
            try {
                comparison.writeCsv(compareCsv);
                System.err.println("[d1-compare] per-cell rows written to " + compareCsv);
            } catch (java.io.IOException e) {
                System.err.println("[d1-compare] could not write " + compareCsv + ": " + e.getMessage());
            }
        }
        System.err.flush();
    }

    /** Cells the decision model could not settle, in their original order. */
    private List<InterpretationCellView> decideWithDecisionModel(
            List<InterpretationCellView> pending,
            List<InterpretationCellView> allCells,
            Map<Long, ReadingOutcome> settled) {
        if (pending.isEmpty()) {
            return pending;
        }
        long start = System.nanoTime();
        List<ParallelCalls.Outcome<CellDecisionClient.Decision>> outcomes = askDecisions(pending, allCells, settled);
        List<InterpretationCellView> deferred = new ArrayList<>();
        int failed = 0;
        int lowConfidence = 0;
        for (int i = 0; i < pending.size(); i++) {
            InterpretationCellView cell = pending.get(i);
            var outcome = outcomes.get(i);
            if (!outcome.ok()) {
                failed++;
                deferred.add(cell);
                continue;
            }
            CellDecisionClient.Decision d = outcome.value();
            CellScale stated = ctx.statedScale(cell);
            if (!d.settles(decisionMinConfidence, stated)) {
                lowConfidence++;
                deferred.add(cell);
                continue;
            }
            String rowLabel = KindTokens.normalizeLabel(ctx.rowLabel(cell));
            String colLabel = KindTokens.normalizeLabel(ctx.columnLabel(cell));
            boolean money = ReadingOutcome.MONEY.equals(d.kind());
            String currency = money ? extractCurrencyFromLabels(rowLabel, colLabel) : "";
            String unit = money ? "" : extractUnitFromLabels(rowLabel, colLabel);
            // A non-money number has no scale, and a scale the sheet states beats the model's.
            String scale = !money ? CellScale.UNIT.wireName() : stated != null ? stated.wireName() : d.scale();
            applyResponse(cell, new CellTypeResponse(null, d.kind(), scale, unit, currency, d.confidenceFor(stated)), settled);
            if (settled.get(cell.cellId()) != null
                    && ReadingOutcome.UNTYPABLE.equals(settled.get(cell.cellId()).refusal)) {
                deferred.add(cell); // answer rejected (e.g. unknown scale)
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        LlmStats.GLOBAL.add("layer-b", "cells_to_decision_model", pending.size());
        LlmStats.GLOBAL.add("layer-b", "cells_settled_decision_model", pending.size() - deferred.size());
        LlmStats.GLOBAL.add("layer-b", "cells_deferred_decision_low_confidence", lowConfidence);
        LlmStats.GLOBAL.add("layer-b", "cells_decision_failed", failed);
        LlmStats.GLOBAL.add("layer-b", "decision_phase_millis", ms);
        System.err.println("[cell-decision] " + (pending.size() - deferred.size()) + "/" + pending.size()
                + " cells settled by decision model in " + ms + "ms; " + lowConfidence
                + " low-confidence and " + failed + " failed deferred to chat LLM");
        System.err.flush();
        return deferred;
    }

    /** Asked in slices so a long run prints progress instead of going quiet. */
    private List<ParallelCalls.Outcome<CellDecisionClient.Decision>> askDecisions(
            List<InterpretationCellView> pending,
            List<InterpretationCellView> allCells,
            Map<Long, ReadingOutcome> settled) {
        List<java.util.concurrent.Callable<CellDecisionClient.Decision>> tasks = new ArrayList<>();
        for (InterpretationCellView cell : pending) {
            String state = formatDecisionState(
                    buildCellTypeRequest(cell, allCells, settled), ctx.region(cell), cell, settled);
            // Shadow mode always asks the scale too, so its CSV can price every gate rule.
            boolean askScale = compareDecisions || ctx.statedScale(cell) == null;
            tasks.add(() -> decisions.decide(state, askScale));
        }
        List<ParallelCalls.Outcome<CellDecisionClient.Decision>> outcomes = new ArrayList<>(tasks.size());
        int slice = Math.max(decisionConcurrency * 8, 50);
        long started = System.nanoTime();
        for (int from = 0; from < tasks.size(); from += slice) {
            int to = Math.min(from + slice, tasks.size());
            outcomes.addAll(ParallelCalls.run(tasks.subList(from, to), decisionConcurrency));
            long failed = outcomes.stream().filter(o -> !o.ok()).count();
            long seconds = Math.max(1, (System.nanoTime() - started) / 1_000_000_000L);
            System.err.println("[cell-decision] asked " + to + "/" + tasks.size() + " cells ("
                    + (to / seconds) + " cells/s, " + failed + " failed)");
            System.err.flush();
        }
        return outcomes;
    }

    /**
     * The decision model's input: one JSON object with a named field for each fact, as the
     * Decisions API recommends when the context has several parts. Facts a cell lacks are left
     * out, so a cell with no region or notes reads as sparsely as before.
     */
    private String formatDecisionState(
            CellTypeRequest request, RegionContext region, InterpretationCellView cellView,
            Map<Long, ReadingOutcome> settled) {
        com.fasterxml.jackson.databind.node.ObjectNode state = MAPPER.createObjectNode();
        if (stateSheet) {
            putIfPresent(state, "sheet", ctx.sheetName(cellView).trim());
        }
        CellScale stated = ctx.statedScale(cellView);
        if (stateStatedScale && stated != null) {
            state.put("stated_scale", stated.wireName());
        }
        if (hasRegionBlock(region)) {
            com.fasterxml.jackson.databind.node.ObjectNode r = state.putObject("region");
            putIfPresent(r, "family", region.scheduleFamily());
            putIfPresent(r, "head", region.packetHead());
            if (!stateSheet) {
                putIfPresent(r, "sheet", region.sheetName()); // the sheet rides in the region block only
            }
            putIfPresent(r, "about", region.about());
        }
        com.fasterxml.jackson.databind.node.ObjectNode cell = state.putObject("cell");
        cell.put("coord", request.coord);
        cell.put("display", request.displayValue);
        putIfPresent(cell, "formula", request.formulaText);
        CellContextBlock.Parts parts = request.context();
        putIfPresent(state, "row_label", parts.rowLabel());
        putIfPresent(state, "column_label", parts.columnLabel());
        putIfPresent(state, "part_of", parts.partOf());
        if (!parts.rowNotes().isEmpty()) {
            com.fasterxml.jackson.databind.node.ArrayNode notes = state.putArray("row_note");
            parts.rowNotes().forEach(notes::add);
        }
        if (stateInputs) {
            putInputs(state, cellView, settled);
        }
        if (!request.neighbors.isEmpty()) {
            com.fasterxml.jackson.databind.node.ArrayNode near = state.putArray("neighbours");
            for (NeighborCell neighbor : request.neighbors) {
                com.fasterxml.jackson.databind.node.ObjectNode n = near.addObject();
                n.put("direction", neighbor.direction);
                n.put("display", neighbor.displayValue);
                n.put("kind", neighbor.type);
            }
        }
        return state.toString();
    }

    /** The most precedents listed for one formula; the rest are only counted. */
    private static final int MAX_INPUTS = 8;

    /**
     * What the formula reads, as computed here: each typed precedent's kind (and scale, for money)
     * and what its row is called. Untyped precedents say nothing, so they are left out.
     */
    private void putInputs(
            com.fasterxml.jackson.databind.node.ObjectNode state, InterpretationCellView cell,
            Map<Long, ReadingOutcome> settled) {
        List<InterpretationCellView> typed = new ArrayList<>();
        for (long id : precedentIds.getOrDefault(cell.cellId(), Set.of())) {
            InterpretationCellView pred = cellsById.get(id);
            ReadingOutcome outcome = settled.get(id);
            if (pred != null && outcome != null && outcome.refusal == null && outcome.kind != null) {
                typed.add(pred);
            }
        }
        if (typed.isEmpty()) {
            return;
        }
        typed.sort(java.util.Comparator.comparingLong(InterpretationCellView::worksheetId)
                .thenComparingInt(InterpretationCellView::rowNum)
                .thenComparingInt(InterpretationCellView::colNum));
        String ownSheet = ctx.sheetName(cell);
        com.fasterxml.jackson.databind.node.ArrayNode inputs = state.putArray("inputs");
        for (InterpretationCellView pred : typed.subList(0, Math.min(MAX_INPUTS, typed.size()))) {
            ReadingOutcome outcome = settled.get(pred.cellId());
            com.fasterxml.jackson.databind.node.ObjectNode in = inputs.addObject();
            String sheet = ctx.sheetName(pred);
            in.put("ref", sheet.equals(ownSheet) || sheet.isBlank() ? pred.coord() : sheet.trim() + "!" + pred.coord());
            in.put("kind", outcome.kind);
            if (ReadingOutcome.MONEY.equals(outcome.kind) && outcome.scale != null) {
                in.put("scale", outcome.scale.wireName());
            }
            putIfPresent(in, "row_label", ctx.rowLabel(pred));
        }
        if (typed.size() > MAX_INPUTS) {
            state.put("inputs_not_shown", typed.size() - MAX_INPUTS);
        }
    }

    /** Whether the decision state carries a {@code region} block for this region. */
    private static boolean hasRegionBlock(RegionContext region) {
        return !region.scheduleFamily().isBlank() || !region.about().isBlank();
    }

    private static void putIfPresent(com.fasterxml.jackson.databind.node.ObjectNode node, String name, String value) {
        if (value != null && !value.isBlank()) {
            node.put(name, value);
        }
    }

    /**
     * Get the dynamic dictionary. Must be called after classification to persist learned terms.
     */
    public DynamicKindTokens getDynamicDictionary() {
        return dynamicDict;
    }

    private void applyBatchOutcome(
            List<InterpretationCellView> batch,
            List<CellTypeRequest> requests,
            List<RegionContext> regions,
            ParallelCalls.Outcome<List<CellTypeResponse>> outcome,
            Map<Long, ReadingOutcome> settled) {
        if (!outcome.ok()) {
            System.err.println("[llm-fallback] Failed to classify batch: " + outcome.error().getMessage());
            reask(batch, requests, regions, allIndexes(batch.size()), settled);
            return;
        }
        List<CellTypeResponse> responses = outcome.value();
        boolean numbered = !responses.isEmpty() && responses.stream().allMatch(r -> r.cell() != null);
        if (numbered) {
            // Answers carry their cell number, so a missing, extra or reordered answer costs only
            // that cell: the rest are applied and just the unanswered ones are asked again.
            Map<Integer, CellTypeResponse> byCell = new HashMap<>();
            for (CellTypeResponse response : responses) {
                if (response.cell() >= 1 && response.cell() <= batch.size()) {
                    byCell.putIfAbsent(response.cell(), response); // first answer for a cell wins
                }
            }
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                CellTypeResponse response = byCell.get(i + 1);
                if (response == null) {
                    missing.add(i);
                } else {
                    applyResponse(batch.get(i), response, settled);
                }
            }
            if (!missing.isEmpty()) {
                System.err.println("[llm-fallback] Batch answered " + (batch.size() - missing.size()) + " of "
                        + batch.size() + " cells; asking again only for the " + missing.size() + " missing");
                reask(batch, requests, regions, missing, settled);
            }
            return;
        }
        if (responses.size() == batch.size()) {
            for (int i = 0; i < batch.size(); i++) {
                applyResponse(batch.get(i), responses.get(i), settled);
            }
            return;
        }
        // Unnumbered answers whose count is wrong: positions no longer line up with cells.
        System.err.println("[llm-fallback] Failed to classify batch: expected " + batch.size()
                + " responses, got " + responses.size());
        reask(batch, requests, regions, allIndexes(batch.size()), settled);
    }

    private static List<Integer> allIndexes(int n) {
        List<Integer> all = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            all.add(i);
        }
        return all;
    }

    /** Ask again, one cell at a time (each call already tries every configured model). */
    private void reask(
            List<InterpretationCellView> batch,
            List<CellTypeRequest> requests,
            List<RegionContext> regions,
            List<Integer> indexes,
            Map<Long, ReadingOutcome> settled) {
        for (int i : indexes) {
            InterpretationCellView cell = batch.get(i);
            try {
                applyResponse(cell, classifyCell(requests.get(i), regions.get(i)), settled);
            } catch (Exception ex) {
                System.err.println("[llm-fallback] Failed to classify " + cell.coord() + ": " + ex.getMessage());
            }
        }
    }

    /** Settle one LLM answer and stage it as evidence for the dictionary. */
    private void applyResponse(
            InterpretationCellView cell, CellTypeResponse response, Map<Long, ReadingOutcome> settled) {
        if (response.confidence < MIN_CONFIDENCE) {
            return;
        }
        CellScale scale = parseScale(response.scale);
        if (scale == null) {
            System.err.println("[llm-fallback] Unknown scale '" + response.scale + "' for " + cell.coord());
            return;
        }
        // A scale the sheet or region itself states beats the model's guess for money.
        CellScale stated = ReadingOutcome.MONEY.equals(response.kind) ? ctx.statedScale(cell) : null;
        if (stated != null && stated != scale) {
            System.err.println("[llm-fallback] " + cell.coord() + ": model said " + scale.wireName()
                    + " but the sheet states " + stated.wireName() + "; using " + stated.wireName());
            scale = stated;
        }
        if (ReadingOutcome.MONEY.equals(response.kind) && stated == null && scale == CellScale.UNIT) {
            // "Unit" is what a model says when it finds no scale word, not a reading: money nothing
            // states has no scale rather than rupees, and the sheets that read it may fix it. Unless
            // the cell's own labels say rupees, it is left unstated like the dictionary leaves it.
            CellScale labelled = extractScaleFromLabels(
                    KindTokens.normalizeLabel(ctx.rowLabel(cell)), KindTokens.normalizeLabel(ctx.columnLabel(cell)), cell);
            if (labelled == null) {
                settled.put(cell.cellId(), ReadingOutcome.unstated(
                        response.unit, response.currency, ReadingOutcome.DERIVED, unknowns.fresh(cell.cellId())));
                learnFrom(cell, response);
                return;
            }
            scale = labelled;
        }
        settled.put(cell.cellId(), ReadingOutcome.typed(
                response.kind, scale, response.unit, response.currency, ReadingOutcome.DERIVED));
        learnFrom(cell, response);
    }

    private static CellScale parseScale(String wire) {
        if (wire == null || wire.isBlank()) {
            return CellScale.UNIT;
        }
        try {
            return CellScale.fromWire(wire);
        } catch (IllegalArgumentException e) {
            return CellScale.fromText(wire); // "lacs", "crores" ... or null when it names no scale
        }
    }

    /**
     * Stage the row label as evidence only when the region is a known main packet and the
     * deterministic pass could not have typed the label itself.
     */
    private void learnFrom(InterpretationCellView cell, CellTypeResponse response) {
        RegionContext region = ctx.region(cell);
        if (!region.known() || !region.learnable()) {
            return;
        }
        String rowLabel = ctx.rowLabel(cell);
        String combined = KindTokens.normalizeLabel(rowLabel + " " + ctx.columnLabel(cell));
        if (staticKind(combined) != null) {
            return;
        }
        dynamicDict.observe(
                region.scheduleFamily(), rowLabel, response.kind, response.unit,
                ctx.workbookKey(), region.sheetName(), cell.rowNum());
    }

    private List<CellTypeResponse> classifyBatchCells(List<CellTypeRequest> requests, List<RegionContext> regions) throws Exception {
        String userMessage = formatBatchUserMessage(requests, regions);
        long llmStart = System.nanoTime();
        // Room for ~120 tokens per answer, so a long batch is not cut off mid-JSON.
        String jsonResponse = llm.classifyCellJson(SYSTEM_PROMPT, userMessage, Math.max(4096, 120 * requests.size()));
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
        System.err.println("[cell-llm] Batch of " + requests.size() + " cells: LLM responded in " + llmMs + "ms");
        return parseBatchCellTypeResponse(jsonResponse, requests.size());
    }

    private static void appendRegion(StringBuilder sb, RegionContext region) {
        if (region.scheduleFamily().isBlank() && region.about().isBlank()) {
            return;
        }
        sb.append("Region: family=").append(region.scheduleFamily());
        if (!region.packetHead().isBlank()) {
            sb.append("; head=").append(region.packetHead());
        }
        if (!region.sheetName().isBlank()) {
            sb.append("; sheet=").append(region.sheetName());
        }
        if (!region.about().isBlank()) {
            sb.append("; about=").append(region.about());
        }
        sb.append("\n\n");
    }

    private String formatBatchUserMessage(List<CellTypeRequest> requests, List<RegionContext> regions) {
        StringBuilder sb = new StringBuilder();
        sb.append("Classify the following ").append(requests.size()).append(" cells");
        sb.append(". A Region line describes every cell after it until the next Region line:\n\n");

        for (int i = 0; i < requests.size(); i++) {
            if (i == 0 || !regions.get(i).equals(regions.get(i - 1))) {
                appendRegion(sb, regions.get(i));
            }
            CellTypeRequest request = requests.get(i);
            sb.append("Cell ").append(i + 1).append(": ").append(request.coord)
                    .append(" (display: \"").append(request.displayValue).append("\"");
            if (!request.formulaText.isBlank()) {
                sb.append(", formula: \"").append(request.formulaText).append("\"");
            }
            sb.append(")\n");

            sb.append(CellContextBlock.lines(request.context(), "  "));

            if (!request.neighbors.isEmpty()) {
                sb.append("  Context:");
                for (NeighborCell neighbor : request.neighbors) {
                    sb.append(" ").append(neighbor.direction).append("=").append(neighbor.displayValue)
                            .append("(").append(neighbor.type).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("Return ONLY JSON, exactly one result per cell, each carrying its own \"cell\" number as given above.")
                .append(" Wrap the ").append(requests.size()).append(" classifications in a JSON object with key 'results':\n");
        sb.append("{\n");
        sb.append("  \"results\": [\n");
        sb.append("    {\"cell\":1,\"kind\":\"money\",\"scale\":\"lakh\",\"unit\":\"\",\"currency\":\"INR\",\"confidence\":0.95},\n");
        sb.append("    {\"cell\":2,\"kind\":\"quantity\",\"scale\":\"unit\",\"unit\":\"pieces\",\"currency\":\"\",\"confidence\":0.90}\n");
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    private List<CellTypeResponse> parseBatchCellTypeResponse(String jsonResponse, int expectedCount) throws Exception {
        List<CellTypeResponse> responses = new ArrayList<>();
        JsonNode root = MAPPER.readTree(jsonResponse);

        // Handle multiple formats: [...], {"cells": [...]}, {"array": [...]}, or any object containing an array
        JsonNode nodes;
        if (root.isArray()) {
            nodes = root;
        } else if (root.isObject()) {
            // Try common wrapper keys first
            if (root.has("cells")) {
                nodes = root.get("cells");
            } else if (root.has("array")) {
                nodes = root.get("array");
            } else if (root.has("results")) {
                nodes = root.get("results");
            } else if (root.has("data")) {
                nodes = root.get("data");
            } else {
                // Find first array in the object
                nodes = null;
                for (JsonNode field : root) {
                    if (field.isArray()) {
                        nodes = field;
                        break;
                    }
                }
                if (nodes == null) {
                    throw new IllegalArgumentException("No array found in response object");
                }
            }
            if (!nodes.isArray()) {
                throw new IllegalArgumentException("Expected array value, got: " + nodes.getNodeType());
            }
        } else {
            throw new IllegalArgumentException("Expected JSON array or object with array, got: " + jsonResponse.substring(0, Math.min(100, jsonResponse.length())));
        }

        for (int i = 0; i < nodes.size(); i++) {
            JsonNode node = nodes.get(i);
            String kind = node.get("kind").asText().trim().toLowerCase(java.util.Locale.ROOT);
            String scale = node.get("scale").asText();
            String unit = node.get("unit").asText("");
            String currency = node.get("currency").asText("");
            double confidence = node.get("confidence").asDouble(0.0);

            if (!List.of("money", "quantity", "rate", "percent", "count", "ratio").contains(kind)) {
                // One unusable item must not cost the whole batch: its cell is simply re-asked.
                System.err.println("[llm-fallback] Ignoring answer " + (i + 1) + " with invalid kind '" + kind + "'");
                continue;
            }

            Integer cellNumber = node.hasNonNull("cell") && node.get("cell").canConvertToInt()
                    ? node.get("cell").asInt() : null;
            responses.add(new CellTypeResponse(cellNumber, kind, scale, unit, currency, confidence));
        }

        return responses;
    }

    private CellTypeRequest buildCellTypeRequest(
            InterpretationCellView cell, List<InterpretationCellView> allCells, Map<Long, ReadingOutcome> settled) {
        String rowLabel = ctx.rowLabel(cell);
        String columnLabel = ctx.columnLabel(cell);
        // Extract neighboring cells with their types
        List<NeighborCell> neighbors = extractNeighbors(cell, allCells, settled);

        return new CellTypeRequest(
                cell.cellId(),
                cell.worksheetId(),
                cell.coord(),
                cell.displayValue() != null ? cell.displayValue() : "",
                cell.numericValue() != null ? cell.numericValue() : "",
                rowLabel,
                columnLabel,
                cell.formulaText() != null ? cell.formulaText() : "",
                neighbors,
                ctx.rowNotes(cell),
                ctx.partOf(cell));
    }

    private List<NeighborCell> extractNeighbors(
            InterpretationCellView cell, List<InterpretationCellView> allCells, Map<Long, ReadingOutcome> settled) {
        List<NeighborCell> neighbors = new ArrayList<>();
        int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        String[] directions = {"up", "down", "left", "right"};

        for (int i = 0; i < offsets.length; i++) {
            int targetRow = cell.rowNum() + offsets[i][0];
            int targetCol = cell.colNum() + offsets[i][1];

            for (InterpretationCellView c : allCells) {
                if (c.worksheetId() == cell.worksheetId()
                        && c.rowNum() == targetRow
                        && c.colNum() == targetCol
                        && c.numericValue() != null
                        && !c.numericValue().isBlank()) {
                    ReadingOutcome outcome = settled.get(c.cellId());
                    String type = outcome != null && outcome.kind != null ? outcome.kind : "unknown";
                    neighbors.add(new NeighborCell(directions[i], c.displayValue(), type));
                    break;
                }
            }
        }
        return neighbors;
    }

    private CellTypeResponse classifyCell(CellTypeRequest request, RegionContext region) throws Exception {
        String userMessage = formatUserMessage(request, region);
        long llmStart = System.nanoTime();
        String jsonResponse = llm.classifyCellJson(SYSTEM_PROMPT, userMessage, 2048);
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
        if (jsonResponse == null || jsonResponse.isEmpty()) {
            throw new IllegalStateException("LLM returned empty response for cell " + request.coord);
        }
        System.err.println("[cell-llm] Cell " + request.coord + ": LLM responded in " + llmMs + "ms");
        return parseCellTypeResponse(jsonResponse);
    }

    private String formatUserMessage(CellTypeRequest request, RegionContext region) {
        StringBuilder sb = new StringBuilder();
        appendRegion(sb, region);
        sb.append("Cell: ").append(request.coord).append(" (display: \"").append(request.displayValue).append("\"");
        if (!request.formulaText.isBlank()) {
            sb.append(", formula: \"").append(request.formulaText).append("\"");
        }
        sb.append(")\n");

        sb.append(CellContextBlock.lines(request.context(), ""));

        if (!request.neighbors.isEmpty()) {
            sb.append("Context:\n");
            for (NeighborCell neighbor : request.neighbors) {
                sb.append("  ").append(neighbor.direction).append(": ").append(neighbor.displayValue)
                        .append(" (").append(neighbor.type).append(")\n");
            }
        }

        sb.append("\nClassify as: kind, scale, unit, currency with confidence (0.0-1.0)\n");
        sb.append("Return JSON: {\"kind\":\"...\",\"scale\":\"...\",\"unit\":\"...\",\"currency\":\"...\",\"confidence\":...}\n");

        return sb.toString();
    }

    private CellTypeResponse parseCellTypeResponse(String jsonResponse) throws Exception {
        JsonNode node = MAPPER.readTree(jsonResponse);
        String kind = node.get("kind").asText().trim().toLowerCase(java.util.Locale.ROOT);
        String scale = node.get("scale").asText();
        String unit = node.get("unit").asText("");
        String currency = node.get("currency").asText("");
        double confidence = node.get("confidence").asDouble(0.0);

        // Validate kind
        if (!List.of("money", "quantity", "rate", "percent", "count", "ratio").contains(kind)) {
            throw new IllegalArgumentException("Invalid kind: " + kind);
        }

        return new CellTypeResponse(null, kind, scale, unit, currency, confidence);
    }

    private boolean isNumeric(InterpretationCellView cell) {
        if (cell.isError()) {
            return false;
        }
        if ("number".equals(cell.valueType())) {
            return true;
        }
        if (cell.formulaText() == null || cell.formulaText().isBlank()) {
            return false;
        }
        return cell.numericValue() != null && !cell.numericValue().isBlank();
    }

    record CellTypeRequest(
            long cellId,
            long worksheetId,
            String coord,
            String displayValue,
            String numericValue,
            String rowLabel,
            String columnLabel,
            String formulaText,
            List<NeighborCell> neighbors,
            List<String> rowNotes,
            String partOf) {
        /** The labels, row notes and group the model is told about this cell, written the same in every prompt. */
        CellContextBlock.Parts context() {
            return new CellContextBlock.Parts(rowLabel, columnLabel, rowNotes, partOf);
        }
    }

    record NeighborCell(String direction, String displayValue, String type) {}

    /** {@code cell} is the 1-based number the model echoes back; null when it did not. */
    record CellTypeResponse(Integer cell, String kind, String scale, String unit, String currency, double confidence) {}

    /**
     * Kind named by the static cue tokens in already-normalized label text, else {@code null}. Only
     * confident cues decide: a percent that is the cell's unit, then money words; a label that is a rate
     * or a ratio, or whose only money word can name an amount or a rate (interest, margin), is left to
     * the formula and the models.
     */
    static String staticKind(String normalizedLabels) {
        if (KindTokens.PERCENT_UNIT.matcher(normalizedLabels).find()) {
            return ReadingOutcome.PERCENT;
        }
        if (KindTokens.RATE_LIKE.matcher(normalizedLabels).find()) {
            return null;
        }
        String unambiguous = KindTokens.AMBIGUOUS_AMOUNT.matcher(normalizedLabels).replaceAll(" ");
        if (KindTokens.MONEY_TOKEN.matcher(unambiguous).find()
                || KindTokens.GENERAL_MONEY.matcher(normalizedLabels).find()) {
            return ReadingOutcome.MONEY;
        }
        if (KindTokens.QUANTITY_TOKEN.matcher(normalizedLabels).find()) {
            return ReadingOutcome.QUANTITY;
        }
        return null;
    }

    /**
     * Type a cell without the LLM: first the static {@link KindTokens} cues, then what the
     * dictionary has learned about this row label inside this region's schedule family.
     */
    private ReadingOutcome tryDictionaryBasedTyping(InterpretationCellView cell) {
        String rowLabel = KindTokens.normalizeLabel(ctx.rowLabel(cell));
        String colLabel = KindTokens.normalizeLabel(ctx.columnLabel(cell));
        String combined = rowLabel + " " + colLabel;

        String kind = staticKind(combined);
        if (ReadingOutcome.MONEY.equals(kind)) {
            CellScale scale = extractScaleFromLabels(rowLabel, colLabel, cell);
            String currency = extractCurrencyFromLabels(rowLabel, colLabel);
            return scale == null
                    ? ReadingOutcome.unstated("", currency, ReadingOutcome.INPUT, unknowns.fresh(cell.cellId()))
                    : ReadingOutcome.typed(ReadingOutcome.MONEY, scale, "", currency, ReadingOutcome.INPUT);
        }
        if (ReadingOutcome.PERCENT.equals(kind)) {
            return ReadingOutcome.typed(ReadingOutcome.PERCENT, CellScale.UNIT, "", "", ReadingOutcome.INPUT);
        }
        if (ReadingOutcome.QUANTITY.equals(kind)) {
            return ReadingOutcome.typed(ReadingOutcome.QUANTITY, CellScale.UNIT,
                    extractUnitFromLabels(rowLabel, colLabel), "", ReadingOutcome.INPUT);
        }
        return tryLearnedTyping(cell, colLabel);
    }

    private ReadingOutcome tryLearnedTyping(InterpretationCellView cell, String normalizedColumn) {
        RegionContext region = ctx.region(cell);
        if (!region.known()) {
            return null;
        }
        var learned = dynamicDict.lookup(region.scheduleFamily(), ctx.rowLabel(cell));
        if (learned.isEmpty()) {
            return null;
        }
        String kind = learned.get().kind();
        // Explicit %/quantity cues in the headers were already honoured: the static pass runs first.
        if (ReadingOutcome.MONEY.equals(kind)) {
            // Scale varies by sheet, so it is never remembered: no stated scale, ask the LLM.
            CellScale scale = CellScale.fromText(KindTokens.normalizeLabel(ctx.rowLabel(cell) + " " + normalizedColumn));
            if (scale == null) {
                scale = ctx.statedScale(cell); // the region or sheet title states it for every money line
            }
            if (scale == null) {
                return null;
            }
            return ReadingOutcome.typed(kind, scale, "", extractCurrencyFromLabels("", normalizedColumn),
                    ReadingOutcome.DERIVED);
        }
        return ReadingOutcome.typed(kind, CellScale.UNIT, learned.get().unit(), "", ReadingOutcome.DERIVED);
    }

    private String extractCurrencyFromLabels(String rowLabel, String colLabel) {
        String combined = (rowLabel + " " + colLabel).toLowerCase();
        java.util.regex.Matcher matcher = KindTokens.CURRENCY.matcher(combined);
        if (matcher.find()) {
            String normalized = KindTokens.normalizeCurrency(matcher.group());
            return normalized != null ? normalized : "";
        }
        return "";
    }

    /** The scale the labels or the region or sheet state, or {@code null}: nothing says rupees. */
    private CellScale extractScaleFromLabels(String rowLabel, String colLabel, InterpretationCellView cell) {
        // CellScale.fromText is the one scale-word matcher (lakh/lac/lacs/crore/million/thousand/000s).
        CellScale scale = CellScale.fromText(KindTokens.normalizeLabel(rowLabel + " " + colLabel));
        if (scale == null && (SheetScaleStatement.statesRupees(ctx.rowLabel(cell))
                || SheetScaleStatement.statesRupees(ctx.columnLabel(cell)))) {
            scale = CellScale.UNIT;
        }
        return scale != null ? scale : ctx.statedScale(cell);
    }

    private String extractUnitFromLabels(String rowLabel, String colLabel) {
        String combined = (rowLabel + " " + colLabel).toLowerCase();
        java.util.regex.Matcher matcher = KindTokens.UNIT.matcher(combined);
        if (matcher.find()) {
            return KindTokens.normalizeUnit(matcher.group());
        }
        return "";
    }
}
