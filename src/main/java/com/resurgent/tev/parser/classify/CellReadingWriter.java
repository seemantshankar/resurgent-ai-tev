package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.CellReading;
import com.resurgent.tev.parser.db.FormulaGap;
import com.resurgent.tev.parser.db.FormulaLink;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorksheetRef;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.ingest.FormulaGraphBuilder;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Replaces one parse run's cell readings. Inputs are typed from labels, format,
 * and formula divisors. Formulas are typed from {@code formula_link} until a
 * fixpoint. No row is a guess, and no model is asked.
 */
public final class CellReadingWriter {

    private static final java.util.regex.Pattern CONVERSION = java.util.regex.Pattern.compile(
            "[*/]\\s*(?:1000|100000|1000000|10000000|1000000000)(?![0-9.])");

    private ClassifyTuning tuning = ClassifyTuning.sequential();
    private Set<Long> llmWorksheetIds; // null: every sheet may go to the model
    private CellDecisionClient decisionModelOverride; // null: the environment decides
    private boolean[] stateFacts = {true, true, true}; // sheet, stated scale, inputs

    /** Batch sizes, concurrency, and the sheets whose untyped cells may be sent to the model. */
    CellReadingWriter withTuning(ClassifyTuning tuning, Set<Long> llmWorksheetIds) {
        this.tuning = tuning;
        this.llmWorksheetIds = llmWorksheetIds;
        return this;
    }

    /** Which facts the decision model's state carries beyond the labels: sheet, stated scale, precedent kinds. */
    CellReadingWriter withDecisionStateFacts(boolean sheet, boolean statedScale, boolean inputs) {
        this.stateFacts = new boolean[] {sheet, statedScale, inputs};
        return this;
    }

    /** Use this decision model instead of the one the environment configures (replays and tests). */
    CellReadingWriter withDecisionModel(CellDecisionClient decisionModel) {
        this.decisionModelOverride = decisionModel;
        return this;
    }

    public void replace(WorkspaceRepository repo, long parseRunId) throws SQLException {
        replace(repo, parseRunId, null);
    }

    void replace(WorkspaceRepository repo, long parseRunId, ClassifierLlm llm) throws SQLException {
        LlmStats.GLOBAL.enterStage("layer-b");
        java.time.Instant started = java.time.Instant.now();
        try {
            replaceCells(repo, parseRunId, llm);
        } finally {
            Double numeric = LlmStats.GLOBAL.stat("layer-b", "cells_numeric");
            LlmStats.GLOBAL.timing("layer-b", started, java.time.Instant.now(),
                    numeric == null ? null : numeric.intValue());
        }
    }

    private void replaceCells(WorkspaceRepository repo, long parseRunId, ClassifierLlm llm) throws SQLException {
        List<InterpretationCellView> cells = repo.selectInterpretationCellsForParseRun(parseRunId);
        Map<Long, InterpretationCellView> byId = InterpretationEvidenceResolver.indexCells(cells);
        Map<Long, Set<Long>> members = InterpretationEvidenceResolver.indexMembers(
                repo.selectCandidateMembersForParseRun(parseRunId));
        List<CandidateRow> candidates = repo.selectCandidatesForParseRun(parseRunId);
        Map<Long, CandidateRow> candidatesById = new HashMap<>();
        for (CandidateRow candidate : candidates) {
            candidatesById.put(candidate.candidateId(), candidate);
        }
        Map<Long, List<CandidateRow>> owners =
                InterpretationEvidenceResolver.indexOwners(candidates, members);
        InterpretationEvidenceResolver.ResolveCache cache =
                new InterpretationEvidenceResolver.ResolveCache(byId);
        cache.useGeometry(repo.selectHeaderGeometry(parseRunId));

        Map<Long, Set<Long>> precedents = new LinkedHashMap<>();
        for (FormulaLink link : repo.selectFormulaLinksForParseRun(parseRunId)) {
            precedents.computeIfAbsent(link.fromCellId(), id -> new HashSet<>()).add(link.toCellId());
        }
        Map<Long, List<String>> gaps = new HashMap<>();
        for (FormulaGap gap : repo.selectFormulaGapsForParseRun(parseRunId)) {
            gaps.computeIfAbsent(gap.fromCellId(), id -> new ArrayList<>()).add(gap.reason());
        }
        Map<String, Long> sheetIds = new HashMap<>();
        Map<Long, String> sheetNames = new HashMap<>();
        for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
            // Registered by its exact name and, when padded, by its trimmed name too: a formula may
            // spell the padded name either way, and two sheets differing only by padding must not clobber.
            sheetIds.put(sheet.sheetName().toLowerCase(Locale.ROOT), sheet.worksheetId());
            sheetIds.putIfAbsent(sheet.sheetName().trim().toLowerCase(Locale.ROOT), sheet.worksheetId());
            sheetNames.put(sheet.worksheetId(), sheet.sheetName());
        }
        Map<Long, String> formats = repo.selectNumberFormatsForParseRun(parseRunId);
        String home = HomeCurrency.resolve(cells);

        Map<Long, PacketDisposition> dispositions = new HashMap<>();
        for (PacketDisposition d : repo.selectPacketDispositionsForParseRun(parseRunId)) {
            dispositions.put(d.candidateId(), d);
        }
        StatedScales stated = new StatedScales(cells, owners, dispositions);
        UnstatedScales unknowns = new UnstatedScales();

        Set<Long> numericIds = new HashSet<>();
        for (InterpretationCellView cell : cells) {
            if (isNumeric(cell)) {
                numericIds.add(cell.cellId());
            }
        }

        LlmStats.GLOBAL.put("layer-b", "cells_total", cells.size());
        LlmStats.GLOBAL.put("layer-b", "cells_numeric", numericIds.size());
        Map<Long, ReadingOutcome> settled = new HashMap<>();
        List<InterpretationCellView> formulas = new ArrayList<>();
        for (InterpretationCellView cell : cells) {
            if (!numericIds.contains(cell.cellId())) {
                continue;
            }
            String refusal = gapRefusal(gaps.get(cell.cellId()));
            if (refusal != null) {
                settled.put(cell.cellId(), ReadingOutcome.refused(refusal));
                continue;
            }
            if (!precedents.containsKey(cell.cellId())) {
                String[] labels = InterpretationEvidenceResolver.resolvedHeaderTexts(
                        parseRunId, cell, cache, owners, members, candidatesById);
                settled.put(cell.cellId(), InputReading.type(
                        cell, labels[0], labels[1], formats.get(cell.cellId()), home, stated.of(cell), unknowns));
            } else {
                formulas.add(cell);
            }
        }

        propagate(formulas, precedents, numericIds, byId, sheetIds, settled, stated, unknowns);

        // Post-process: infer types for untypable cells based on structural context
        new CellReadingInferencer(cells).infer(settled);

        // Use LLM for remaining unclassified cells (if LLM is provided)
        DynamicKindTokens dynamicDict = null;
        try {
            if (llm != null) {
                CellContext context = new ResolverCellContext(
                        parseRunId, cache, owners, members, candidatesById, dispositions, sheetNames,
                        repo.selectSourceFileHashForParseRun(parseRunId), stated);
                CellTypeClassifierLlm classifier = new CellTypeClassifierLlm(repo, llm)
                        .withBatching(tuning.cellBatchSize(), tuning.concurrency())
                        .withWorksheetScope(llmWorksheetIds)
                        .withUnstatedScales(unknowns)
                        .withPrecedents(precedents, byId)
                        .withDecisionStateFacts(stateFacts[0], stateFacts[1], stateFacts[2]);
                CellDecisionClient decisionModel =
                        decisionModelOverride != null ? decisionModelOverride : LlmEnvironment.decisionClientOrNull();
                if (decisionModel != null) {
                    // An injected model runs on the defaults, whatever the environment says.
                    boolean fromEnvironment = decisionModelOverride == null;
                    classifier.withDecisionModel(decisionModel)
                            .withDecisionTuning(
                                    fromEnvironment
                                            ? LlmEnvironment.decisionMinConfidence(LlmEnvironment.load(), decisionModel.model())
                                            : CellTypeClassifierLlm.DEFAULT_DECISION_MIN_CONFIDENCE,
                                    fromEnvironment ? LlmEnvironment.decisionConcurrency()
                                            : CellTypeClassifierLlm.DEFAULT_DECISION_CONCURRENCY);
                    if (fromEnvironment && LlmEnvironment.decisionCompareRequested()) {
                        classifier.withDecisionComparison(LlmEnvironment.decisionCompareCsv());
                    }
                }
                dynamicDict = classifier.getDynamicDictionary();
                // Inputs first, then let formulas follow them, then whatever is still untyped.
                List<InterpretationCellView> inputCells = new ArrayList<>();
                for (InterpretationCellView cell : cells) {
                    if (!precedents.containsKey(cell.cellId())) {
                        inputCells.add(cell);
                    }
                }
                System.err.println("[cell-reading] pass 1 of 2: " + inputCells.size() + " input cells");
                System.err.flush();
                classifier.classifyRemaining(inputCells, settled, context);
                boolean retyped = false;
                for (InterpretationCellView cell : formulas) {
                    ReadingOutcome now = settled.get(cell.cellId());
                    if (now != null && ReadingOutcome.UNTYPABLE.equals(now.refusal)) {
                        settled.remove(cell.cellId());
                        retyped = true;
                    }
                }
                if (retyped) {
                    propagate(formulas, precedents, numericIds, byId, sheetIds, settled, stated, unknowns);
                }
                System.err.println("[cell-reading] pass 2 of 2: " + formulas.size() + " formula cells");
                System.err.flush();
                typeFormulas(classifier, context, formulas, precedents, numericIds, byId, sheetIds, settled, stated, unknowns);
            }
        } finally {
            // Keep what was learned even if the LLM pass failed part-way; never fails the parse.
            if (dynamicDict != null) {
                dynamicDict.persist();
                dynamicDict.printReport();
            }
        }

        reconcile(formulas, precedents, numericIds, byId, sheetIds, settled, stated, unknowns, sheetNames);

        long untypedFinal = numericIds.stream()
                .map(settled::get)
                .filter(o -> o != null && ReadingOutcome.UNTYPABLE.equals(o.refusal))
                .count();
        LlmStats.GLOBAL.put("layer-b", "cells_untypable_final", untypedFinal);
        settleUnstatedScales(cells, settled, unknowns, sheetNames);
        reportScaleConflicts(cells, settled, stated);

        List<CellReading> rows = new ArrayList<>();
        for (InterpretationCellView cell : cells) {
            ReadingOutcome outcome = settled.get(cell.cellId());
            if (outcome == null) {
                continue;
            }
            rows.add(toRow(parseRunId, cell, outcome));
        }
        repo.replaceCellReadings(parseRunId, rows);

    }

    /**
     * Money the sheet or region says is in one scale but that was settled in another. The
     * arithmetic or the label wins (a {@code /100000} legitimately changes scale), so this
     * only reports: it is the list a person reads to find a wrong header or a wrong rule.
     */
    private static void reportScaleConflicts(
            List<InterpretationCellView> cells, Map<Long, ReadingOutcome> settled, StatedScales stated) {
        int count = 0;
        List<String> samples = new ArrayList<>();
        for (InterpretationCellView cell : cells) {
            ReadingOutcome outcome = settled.get(cell.cellId());
            CellScale says = stated.of(cell);
            if (outcome == null || says == null || outcome.refusal != null
                    || !ReadingOutcome.MONEY.equals(outcome.kind) || outcome.scale == null || outcome.scale == says
                    || (cell.formulaText() != null && CONVERSION.matcher(cell.formulaText()).find())) {
                continue; // a formula that converts scale is the explanation, not a conflict
            }
            count++;
            if (samples.size() < 10) {
                samples.add(cell.coord() + " is " + outcome.scale.wireName() + ", sheet says " + says.wireName());
            }
        }
        if (count > 0) {
            System.err.println("[cell-reading] " + count + " money cells differ from the scale their region or "
                    + "sheet states (the label wins); first: " + String.join("; ", samples));
            System.err.flush();
        }
    }

    /**
     * Gives unstated money the scale the sheets that read it agree on (an {@code Interest}
     * schedule with no unit text, read by a cash flow that states lakhs, is in lakhs). Money
     * whose readers disagree, or that nothing stated reads, keeps no scale and no absolute
     * amount: it is listed for review, not guessed.
     */
    private static void settleUnstatedScales(
            List<InterpretationCellView> cells,
            Map<Long, ReadingOutcome> settled,
            UnstatedScales unknowns,
            Map<Long, String> sheetNames) {
        Map<Long, InterpretationCellView> byId = new HashMap<>();
        for (InterpretationCellView cell : cells) {
            byId.put(cell.cellId(), cell);
        }
        int inferred = 0;
        int unread = 0;
        Map<Integer, String> conflicts = new LinkedHashMap<>();
        for (InterpretationCellView cell : cells) {
            ReadingOutcome outcome = settled.get(cell.cellId());
            if (outcome == null || !outcome.scaleUnstated()) {
                continue;
            }
            Map<CellScale, Long> required = unknowns.required(outcome.scaleUnknown);
            if (required.size() == 1) {
                settled.put(cell.cellId(), outcome.withScale(required.keySet().iterator().next(), true));
                inferred++;
            } else if (required.isEmpty()) {
                unread++;
            } else {
                conflicts.computeIfAbsent(unknowns.root(outcome.scaleUnknown), root -> {
                    List<String> claims = new ArrayList<>();
                    required.forEach((scale, by) -> claims.add(
                            scale.wireName() + " by " + where(byId.get(by), sheetNames)));
                    return where(byId.get(unknowns.originOf(root)), sheetNames) + " read as " + String.join(", ", claims);
                });
            }
        }
        if (inferred + unread + conflicts.size() > 0) {
            System.err.println("[cell-reading] unstated money scale: " + inferred + " cells took the scale "
                    + "the sheets reading them state; " + unread + " are read by nothing that states one (no scale)"
                    + (conflicts.isEmpty() ? "" : "; " + conflicts.size() + " groups read in conflicting scales "
                            + "(no scale): " + String.join("; ", conflicts.values().stream().limit(10).toList())));
            System.err.flush();
        }
    }

    private static String where(InterpretationCellView cell, Map<Long, String> sheetNames) {
        return cell == null ? "?" : sheetNames.getOrDefault(cell.worksheetId(), "?") + "!" + cell.coord();
    }

    /**
     * Types formula cells from their precedents until nothing more settles. Run again after the
     * fallback has typed inputs, so a formula built on a cell the fallback just typed (a
     * difference of two lakh subtotals) follows it instead of being typed on its own. Money a
     * formula left unstated takes the scale its own region or sheet states, and that statement
     * then fixes the scale of what it read.
     */
    private static void propagate(
            List<InterpretationCellView> formulas,
            Map<Long, Set<Long>> precedents,
            Set<Long> numericIds,
            Map<Long, InterpretationCellView> byId,
            Map<String, Long> sheetIds,
            Map<Long, ReadingOutcome> settled,
            StatedScales stated,
            UnstatedScales unknowns) {
        boolean progressed = true;
        while (progressed) {
            progressed = inferAddends(formulas, precedents, numericIds, settled, unknowns);
            for (InterpretationCellView cell : formulas) {
                if (settled.containsKey(cell.cellId())) {
                    continue;
                }
                Set<Long> preds = precedents.getOrDefault(cell.cellId(), Set.of());
                if (waiting(preds, numericIds, settled)) {
                    continue;
                }
                settled.put(cell.cellId(),
                        deriveFor(cell, preds, numericIds, byId, sheetIds, settled, stated, unknowns));
                progressed = true;
            }
        }
        for (InterpretationCellView cell : formulas) {
            settled.putIfAbsent(cell.cellId(), ReadingOutcome.refused(ReadingOutcome.UNTYPABLE));
        }
    }

    /**
     * Addition needs one kind of quantity. A formula that only adds (SUM, +, -) cells together and reads some
     * typed inputs and some inputs nothing could type tells what the untyped ones are: the kind, and the
     * scale, of the typed ones. A subtotal over "Catering sales", "Restaurant sales" and a row nobody labelled
     * is money in all three, and is then derived by arithmetic instead of being guessed by a model. Applies
     * only when the typed addends agree and outnumber the untyped ones, every other thing it reads is typed, and the cell to type is a
     * plain input (no formula, not refused). Returns whether it typed anything.
     */
    private static boolean inferAddends(
            List<InterpretationCellView> formulas,
            Map<Long, Set<Long>> precedents,
            Set<Long> numericIds,
            Map<Long, ReadingOutcome> settled,
            UnstatedScales unknowns) {
        boolean any = false;
        for (InterpretationCellView cell : formulas) {
            if (settled.containsKey(cell.cellId()) || !ReadingArithmetic.additive(cell.formulaText())) {
                continue;
            }
            List<Long> bare = new ArrayList<>();
            List<ReadingOutcome> typed = new ArrayList<>();
            boolean blocked = false;
            for (long pred : precedents.getOrDefault(cell.cellId(), Set.of())) {
                if (!numericIds.contains(pred)) {
                    continue;
                }
                ReadingOutcome o = settled.get(pred);
                if (o == null) {
                    blocked = true; // a formula not typed yet: wait for it
                    break;
                }
                if (o.typed()) {
                    typed.add(o);
                } else if (o.refusal == null && !precedents.containsKey(pred)) {
                    bare.add(pred); // an input nothing could type
                } else {
                    blocked = true; // refused, or a formula nothing could type
                    break;
                }
            }
            // The typed addends must clearly outnumber the untyped ones: one typed cell among several untyped
            // ones is the weakest evidence, and a wrong typed cell would be copied onto all of them.
            if (blocked || bare.isEmpty() || typed.size() <= bare.size()) {
                continue;
            }
            String kind = typed.get(0).kind;
            String unit = typed.get(0).unit;
            Set<CellScale> known = new java.util.LinkedHashSet<>();
            boolean agree = true;
            for (ReadingOutcome o : typed) {
                agree &= kind.equals(o.kind) && unit.equals(o.unit);
                if (o.scale != null && !o.scaleUnstated()) {
                    known.add(o.scale);
                }
            }
            if (!agree || known.size() > 1) {
                continue;
            }
            String currency = typed.stream().map(o -> o.currency).filter(c -> !c.isEmpty()).findFirst().orElse("");
            for (long input : bare) {
                ReadingOutcome inferred;
                if (!ReadingOutcome.MONEY.equals(kind)) {
                    inferred = ReadingOutcome.typed(kind, CellScale.UNIT, unit, "", ReadingOutcome.DERIVED);
                } else if (known.size() == 1) {
                    CellScale scale = known.iterator().next();
                    inferred = ReadingOutcome.typed(kind, scale, unit, currency, ReadingOutcome.DERIVED)
                            .withScale(scale, true);
                } else {
                    int fresh = unknowns.fresh(input);
                    ReadingOutcome sibling = typed.stream().filter(ReadingOutcome::scaleUnstated).findFirst().orElse(null);
                    if (sibling != null) {
                        unknowns.same(fresh, sibling.scaleUnknown);
                    }
                    inferred = ReadingOutcome.unstated(unit, currency, ReadingOutcome.DERIVED, fresh);
                }
                settled.put(input, inferred);
                LlmStats.GLOBAL.add("layer-b", "cells_typed_by_addends", 1);
                any = true;
            }
        }
        return any;
    }

    /** What the formula's own arithmetic says of this cell, given what its precedents are typed as now. */
    private static ReadingOutcome deriveFor(
            InterpretationCellView cell,
            Set<Long> preds,
            Set<Long> numericIds,
            Map<Long, InterpretationCellView> byId,
            Map<String, Long> sheetIds,
            Map<Long, ReadingOutcome> settled,
            StatedScales stated,
            UnstatedScales unknowns) {
        ReadingOutcome outcome = ReadingArithmetic.derive(
                cell.formulaText(),
                cell.worksheetId(),
                preds,
                numericIds,
                byId,
                sheetIds,
                settled,
                unknowns,
                cell.cellId());
        CellScale says = outcome.scaleUnstated() ? stated.of(cell) : null;
        if (says != null) {
            unknowns.require(outcome.scaleUnknown, says, cell.cellId());
            outcome = outcome.withScale(says, false);
        }
        return outcome;
    }

    /** Safety stop for {@link #reconcile}; each pass must change a cell to continue. */
    private static final int MAX_RECONCILE_PASSES = 20;

    /** Kinds of quantity that cannot be one another: a money cell is never a ratio. Percent and ratio are one. */
    private static String dimension(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case ReadingOutcome.PERCENT, ReadingOutcome.RATIO -> "dimensionless";
            case ReadingOutcome.COUNT, ReadingOutcome.QUANTITY -> "amount";
            default -> kind; // money, rate
        };
    }

    /**
     * Formula authority (ADR 0025 section 3), applied last. Every stage that types a formula before its
     * inputs are known (a label the dictionary read, a column's majority, a model's answer) guesses; the
     * arithmetic of the formula, once what it reads is typed, does not. A typed formula cell whose own
     * arithmetic, over inputs typed by now, gives another dimension (money where it divides money by money)
     * takes the arithmetic's reading, and what was built on it is checked again. Arithmetic that cannot
     * read the formula says nothing, and the earlier answer stands.
     *
     * <p>Only the dimension is overridden (money, rate, ratio or percent, count or quantity): a percent
     * the label chose over the arithmetic's ratio, a count over a quantity, is the same dimension and stays.
     */
    private static void reconcile(
            List<InterpretationCellView> formulas,
            Map<Long, Set<Long>> precedents,
            Set<Long> numericIds,
            Map<Long, InterpretationCellView> byId,
            Map<String, Long> sheetIds,
            Map<Long, ReadingOutcome> settled,
            StatedScales stated,
            UnstatedScales unknowns,
            Map<Long, String> sheetNames) {
        Map<String, Integer> changes = new java.util.TreeMap<>();
        List<String> samples = new ArrayList<>();
        int overridden = 0;
        for (int pass = 0; pass < MAX_RECONCILE_PASSES; pass++) {
            boolean changed = false;
            for (InterpretationCellView cell : formulas) {
                ReadingOutcome now = settled.get(cell.cellId());
                Set<Long> preds = precedents.getOrDefault(cell.cellId(), Set.of());
                if (now == null || !now.typed() || preds.isEmpty()) {
                    continue;
                }
                ReadingOutcome derived = deriveFor(cell, preds, numericIds, byId, sheetIds, settled, stated, unknowns);
                if (!derived.typed() || dimension(derived.kind).equals(dimension(now.kind))) {
                    continue;
                }
                settled.put(cell.cellId(), derived);
                changed = true;
                overridden++;
                changes.merge(now.kind + "->" + derived.kind, 1, Integer::sum);
                if (samples.size() < 60) {
                    samples.add(where(cell, sheetNames) + " " + now.kind + "->" + derived.kind);
                }
            }
            if (!changed) {
                break;
            }
        }
        if (overridden > 0) {
            System.err.println("[cell-reading] formula authority: " + overridden + " typed formula cells took the "
                    + "reading their own arithmetic gives " + changes + "; first " + samples.size() + ": " + String.join("; ", samples));
            System.err.flush();
        }
        LlmStats.GLOBAL.put("layer-b", "cells_overridden_by_arithmetic", overridden);
    }

    /** Safety stop for the rounds of {@link #typeFormulas}; every round must settle a cell to continue. */
    private static final int MAX_ROUNDS = 50;

    /**
     * Pass 2: types the formulas the arithmetic could not. A formula follows the cells typed before
     * it, whichever stage typed them, so every stage that settles a cell is followed by another
     * {@link #propagate}, and the next stage sees only what is still untyped.
     *
     * <ol>
     *   <li>The dictionary types what its labels say; what waited on those cells follows.
     *   <li>Rounds: the decision model, then the chat model for what it was unsure of, are asked only
     *       about cells that wait on no other untyped formula; their answers are propagated before
     *       the next round, so a formula built on a cell they just typed is never asked about.
     *   <li>What is left (a chain whose first cell nothing could type) goes to the models once, all at
     *       a time, as before. No cell is asked of the same model twice.
     * </ol>
     */
    private static void typeFormulas(
            CellTypeClassifierLlm classifier,
            CellContext context,
            List<InterpretationCellView> formulas,
            Map<Long, Set<Long>> precedents,
            Set<Long> numericIds,
            Map<Long, InterpretationCellView> byId,
            Map<String, Long> sheetIds,
            Map<Long, ReadingOutcome> settled,
            StatedScales stated,
            UnstatedScales unknowns) {
        classifier.useContext(context);
        List<InterpretationCellView> untyped = classifier.untypable(formulas, settled);
        if (untyped.isEmpty()) {
            return;
        }
        classifier.typeByDictionary(untyped, settled);
        long followed = repropagate(
                formulas, precedents, numericIds, byId, sheetIds, settled, stated, unknowns, classifier::inScope);

        int rounds = 0;
        while (rounds < MAX_ROUNDS) {
            untyped = classifier.untypable(formulas, settled);
            Set<Long> waitingIds = new HashSet<>();
            untyped.forEach(c -> waitingIds.add(c.cellId()));
            List<InterpretationCellView> ready = new ArrayList<>();
            for (InterpretationCellView cell : untyped) {
                // A cell waits only on an untyped formula some stage may still type; an untyped input
                // or a formula outside the model's scope is not going to change.
                boolean waits = false;
                for (long pred : precedents.getOrDefault(cell.cellId(), Set.of())) {
                    if (pred != cell.cellId() && waitingIds.contains(pred)) {
                        waits = true;
                        break;
                    }
                }
                if (!waits) {
                    ready.add(cell);
                }
            }
            if (ready.isEmpty()) {
                break;
            }
            rounds++;
            int before = untyped.size();
            classifier.typeByChat(classifier.typeByDecisionModel(ready, formulas, settled), formulas, settled);
            long typed = repropagate(
                    formulas, precedents, numericIds, byId, sheetIds, settled, stated, unknowns, classifier::inScope);
            followed += typed;
            int after = classifier.untypable(formulas, settled).size();
            System.err.println("[cell-reading] round " + rounds + ": asked about " + ready.size() + " of "
                    + before + " untyped formulas; " + typed + " more followed by arithmetic; " + after + " left");
            System.err.flush();
            if (after >= before) {
                break;
            }
        }

        untyped = classifier.untypable(formulas, settled);
        if (!untyped.isEmpty()) {
            classifier.typeByChat(classifier.typeByDecisionModel(untyped, formulas, settled), formulas, settled);
            followed += repropagate(
                    formulas, precedents, numericIds, byId, sheetIds, settled, stated, unknowns, classifier::inScope);
        }
        classifier.finishComparison(settled);
        LlmStats.GLOBAL.add("layer-b", "cells_typed_repropagation", followed);
        LlmStats.GLOBAL.add("layer-b", "decision_rounds", rounds);
    }

    /**
     * Forgets the formulas still marked untypable and types them again from what is typed now.
     * Returns how many got a type that way.
     */
    private static long repropagate(
            List<InterpretationCellView> formulas,
            Map<Long, Set<Long>> precedents,
            Set<Long> numericIds,
            Map<Long, InterpretationCellView> byId,
            Map<String, Long> sheetIds,
            Map<Long, ReadingOutcome> settled,
            StatedScales stated,
            UnstatedScales unknowns,
            java.util.function.Predicate<InterpretationCellView> counted) {
        List<InterpretationCellView> retry = new ArrayList<>();
        for (InterpretationCellView cell : formulas) {
            ReadingOutcome now = settled.get(cell.cellId());
            if (now != null && ReadingOutcome.UNTYPABLE.equals(now.refusal)) {
                settled.remove(cell.cellId());
                retry.add(cell);
            }
        }
        if (retry.isEmpty()) {
            return 0;
        }
        propagate(formulas, precedents, numericIds, byId, sheetIds, settled, stated, unknowns);
        long typed = 0;
        for (InterpretationCellView cell : retry) {
            ReadingOutcome now = settled.get(cell.cellId());
            if (counted.test(cell) && now != null && now.refusal == null) {
                typed++;
            }
        }
        return typed;
    }

    private static boolean waiting(Set<Long> preds, Set<Long> numericIds, Map<Long, ReadingOutcome> settled) {
        for (long pred : preds) {
            if (numericIds.contains(pred) && !settled.containsKey(pred)) {
                return true;
            }
        }
        return false;
    }

    private static String gapRefusal(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        boolean external = false;
        boolean other = false;
        for (String reason : reasons) {
            if (FormulaGraphBuilder.CYCLE.equals(reason)) {
                return ReadingOutcome.CYCLE;
            }
            if (FormulaGraphBuilder.EXTERNAL.equals(reason)) {
                external = true;
            } else {
                other = true;
            }
        }
        if (external) {
            return ReadingOutcome.EXTERNAL;
        }
        return other ? ReadingOutcome.UNTYPABLE : null;
    }

    private static boolean isNumeric(InterpretationCellView cell) {
        if (cell.isError()) {
            return false;
        }
        if ("number".equals(cell.valueType())) {
            return true;
        }
        if (cell.formulaText() == null || cell.formulaText().isBlank()) {
            return false;
        }
        return shownNumber(cell) != null;
    }

    private static CellReading toRow(long parseRunId, InterpretationCellView cell, ReadingOutcome outcome) {
        String absolute = null;
        if (outcome.typed() && ReadingOutcome.MONEY.equals(outcome.kind)) {
            absolute = absoluteAmount(shownNumber(cell), outcome.scale);
        }
        String basis = null;
        if (outcome.typed() && ReadingOutcome.MONEY.equals(outcome.kind) && outcome.scale != null) {
            basis = outcome.scaleInferred ? CellReading.SCALE_INFERRED : CellReading.SCALE_STATED;
        }
        return new CellReading(
                parseRunId,
                cell.cellId(),
                outcome.kind,
                outcome.scale == null ? null : outcome.scale.wireName(),
                outcome.unit,
                outcome.currency,
                absolute,
                outcome.typeSource,
                outcome.refusal,
                basis);
    }

    private static String shownNumber(InterpretationCellView cell) {
        if (parseable(cell.numericValue())) {
            return cell.numericValue().trim();
        }
        if (parseable(cell.cachedValue())) {
            return cell.cachedValue().trim();
        }
        return null;
    }

    private static boolean parseable(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            new BigDecimal(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Displayed figure times the scale. {@code 12.5} lakhs is {@code 1250000}. */
    static String absoluteAmount(String numericValue, CellScale scale) {
        if (numericValue == null || scale == null) {
            return null;
        }
        BigDecimal shown = new BigDecimal(numericValue.trim());
        BigDecimal factor = BigDecimal.valueOf(Math.round(scale.multiplier()));
        return shown.multiply(factor).stripTrailingZeros().toPlainString();
    }
}
