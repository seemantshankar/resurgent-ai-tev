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

    public void replace(WorkspaceRepository repo, long parseRunId) throws SQLException {
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
        for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
            sheetIds.put(sheet.sheetName().toLowerCase(Locale.ROOT), sheet.worksheetId());
        }
        Map<Long, String> formats = repo.selectNumberFormatsForParseRun(parseRunId);
        String home = HomeCurrency.resolve(cells);

        Set<Long> numericIds = new HashSet<>();
        for (InterpretationCellView cell : cells) {
            if (isNumeric(cell)) {
                numericIds.add(cell.cellId());
            }
        }

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
                        cell, labels[0], labels[1], formats.get(cell.cellId()), home));
            } else {
                formulas.add(cell);
            }
        }

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
                settled.put(cell.cellId(), ReadingArithmetic.derive(
                        cell.formulaText(),
                        cell.worksheetId(),
                        preds,
                        numericIds,
                        byId,
                        sheetIds,
                        settled));
                progressed = true;
            }
        }
        for (InterpretationCellView cell : formulas) {
            settled.putIfAbsent(cell.cellId(), ReadingOutcome.refused(ReadingOutcome.UNTYPABLE));
        }

        // Post-process: infer types for untypable cells based on structural context
        new CellReadingInferencer(cells).infer(settled);

        // Optional: Use LLM for remaining unclassified cells
        // new CellTypeClassifierLlm(repo, llm).classifyRemaining(cells, settled);
        // See: LLM fallback comment below

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
        return new CellReading(
                parseRunId,
                cell.cellId(),
                outcome.kind,
                outcome.scale == null ? null : outcome.scale.wireName(),
                outcome.unit,
                outcome.currency,
                absolute,
                outcome.typeSource,
                outcome.refusal);
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
