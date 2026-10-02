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

    /** Batch sizes, concurrency, and the sheets whose untyped cells may be sent to the model. */
    CellReadingWriter withTuning(ClassifyTuning tuning, Set<Long> llmWorksheetIds) {
        this.tuning = tuning;
        this.llmWorksheetIds = llmWorksheetIds;
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
            sheetIds.put(sheet.sheetName().toLowerCase(Locale.ROOT), sheet.worksheetId());
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
                        .withUnstatedScales(unknowns);
                CellDecisionClient decisionModel = LlmEnvironment.decisionClientOrNull();
                if (decisionModel != null) {
                    classifier.withDecisionModel(decisionModel)
                            .withDecisionTuning(
                                    LlmEnvironment.decisionMinConfidence(), LlmEnvironment.decisionConcurrency());
                    if (LlmEnvironment.decisionCompareRequested()) {
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
                classifier.classifyRemaining(formulas, settled, context);
            }
        } finally {
            // Keep what was learned even if the LLM pass failed part-way; never fails the parse.
            if (dynamicDict != null) {
                dynamicDict.persist();
                dynamicDict.printReport();
            }
        }

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
            progressed = false;
            for (InterpretationCellView cell : formulas) {
                if (settled.containsKey(cell.cellId())) {
                    continue;
                }
                Set<Long> preds = precedents.getOrDefault(cell.cellId(), Set.of());
                if (waiting(preds, numericIds, settled)) {
                    continue;
                }
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
                settled.put(cell.cellId(), outcome);
                progressed = true;
            }
        }
        for (InterpretationCellView cell : formulas) {
            settled.putIfAbsent(cell.cellId(), ReadingOutcome.refused(ReadingOutcome.UNTYPABLE));
        }
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
