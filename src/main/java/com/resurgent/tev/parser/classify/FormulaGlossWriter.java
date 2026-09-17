package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * After interpretations and annotations exist, asks the gloss LLM for formula
 * cells and persists prose separately. LLM work stays outside the classify
 * write transaction that produced the annotations.
 *
 * <p>Scope is bounded: annotated formulas on non-noise, non-coverage Candidates
 * (plus any bound formula), capped so a full workbook cannot spawn thousands of
 * external calls. Gloss is best-effort explanation — annotated formulas beyond
 * {@link #MAX_GLOSS_PER_RUN} remain annotated without gloss.
 */
public final class FormulaGlossWriter {

    /** Hard ceiling per classify run — explanation is optional, not a full crawl. */
    static final int MAX_GLOSS_PER_RUN = 12;

    private final FormulaGlossLlm glossLlm;

    public FormulaGlossWriter(FormulaGlossLlm glossLlm) {
        this.glossLlm = Objects.requireNonNull(glossLlm, "glossLlm");
    }

    public int write(WorkspaceRepository repo, long parseRunId) throws SQLException {
        if (glossLlm instanceof NoOpFormulaGlossLlm) {
            return 0;
        }
        Set<Long> eligibleCells = eligibleFormulaCells(repo, parseRunId);
        if (eligibleCells.isEmpty()) {
            return 0;
        }

        List<CellInterpretation> formulas = new ArrayList<>();
        for (CellInterpretation row : repo.selectCellInterpretationsForParseRun(parseRunId)) {
            if (!eligibleCells.contains(row.cellId())) {
                continue;
            }
            if (!"formula".equals(row.valueOrigin())) {
                continue;
            }
            if (row.formulaText() == null || row.formulaText().isBlank()) {
                continue;
            }
            if (repo.selectFormulaAnnotations(parseRunId, row.cellId()).isEmpty()) {
                continue;
            }
            formulas.add(row);
        }
        formulas.sort(Comparator
                .comparing((CellInterpretation row) ->
                        !NomenclatureStatus.BOUND.equals(row.nomenclatureStatus()))
                // Prefer scale-divisor formulas (e.g. /10^5 → lakh) when capping the gloss budget.
                .thenComparing((CellInterpretation row) ->
                        row.formulaText() == null || !row.formulaText().contains("10^"))
                .thenComparingLong(CellInterpretation::cellId));
        if (formulas.size() > MAX_GLOSS_PER_RUN) {
            formulas = new ArrayList<>(formulas.subList(0, MAX_GLOSS_PER_RUN));
        }
        if (formulas.isEmpty()) {
            return 0;
        }

        Map<Long, String> scheduleByCell = scheduleFamilyByCell(repo, parseRunId);
        int written = 0;
        for (CellInterpretation row : formulas) {
            List<FormulaAnnotation> annotations =
                    repo.selectFormulaAnnotations(parseRunId, row.cellId());
            List<InterpretationEvidence> evidence =
                    repo.selectInterpretationEvidence(parseRunId, row.cellId());
            FormulaGlossPrompt prompt = new FormulaGlossPrompt(
                    parseRunId,
                    row.cellId(),
                    row.formulaText(),
                    annotations,
                    evidence,
                    scheduleByCell.get(row.cellId()),
                    row.nomenclaturePath(),
                    row.amountRole(),
                    row.nomenclatureStatus());
            String gloss;
            try {
                gloss = glossLlm.gloss(prompt);
            } catch (RuntimeException e) {
                // Gloss is explanation-only; a single failure must not roll back classify.
                continue;
            }
            if (gloss == null || gloss.isBlank()) {
                continue;
            }
            String cleaned = gloss.trim();
            if (cleaned.length() > 2_000) {
                cleaned = cleaned.substring(0, 2_000).trim();
            }
            repo.updateFormulaGloss(parseRunId, row.cellId(), cleaned);
            written++;
        }
        return written;
    }

    /**
     * Cells on non-noise Candidates (any kind), plus any already-bound cell.
     * Smoke tests that mark unrelated Candidates as orphan/noise stay small;
     * {@link #MAX_GLOSS_PER_RUN} caps the rest.
     */
    private static Set<Long> eligibleFormulaCells(WorkspaceRepository repo, long parseRunId)
            throws SQLException {
        Set<Long> interestingCandidates = new HashSet<>();
        for (CandidateRow candidate : repo.selectCandidatesForParseRun(parseRunId)) {
            var disposition = repo.selectPacketDisposition(parseRunId, candidate.candidateId());
            if (disposition.isEmpty()) {
                continue;
            }
            if (Relevance.NOISE.equals(disposition.get().relevance())) {
                continue;
            }
            interestingCandidates.add(candidate.candidateId());
        }
        Set<Long> cells = new HashSet<>();
        for (long[] pair : repo.selectCandidateMembersForParseRun(parseRunId)) {
            long candidateId = pair[0];
            long cellId = pair[1];
            if (interestingCandidates.contains(candidateId)) {
                cells.add(cellId);
            }
        }
        for (CellInterpretation row : repo.selectCellInterpretationsForParseRun(parseRunId)) {
            if (NomenclatureStatus.BOUND.equals(row.nomenclatureStatus())) {
                cells.add(row.cellId());
            }
        }
        return cells;
    }

    private static Map<Long, String> scheduleFamilyByCell(WorkspaceRepository repo, long parseRunId)
            throws SQLException {
        var candidates = repo.selectCandidatesForParseRun(parseRunId);
        Map<Long, String> familyByCandidate = new HashMap<>();
        for (var candidate : candidates) {
            repo.selectPacketDisposition(parseRunId, candidate.candidateId()).ifPresent(d ->
                    familyByCandidate.put(candidate.candidateId(), d.scheduleFamily()));
        }
        Map<Long, List<Long>> candidatesByCell = new HashMap<>();
        for (long[] pair : repo.selectCandidateMembersForParseRun(parseRunId)) {
            long candidateId = pair[0];
            long cellId = pair[1];
            candidatesByCell.computeIfAbsent(cellId, id -> new ArrayList<>()).add(candidateId);
        }
        Map<Long, String> out = new HashMap<>();
        for (Map.Entry<Long, List<Long>> entry : candidatesByCell.entrySet()) {
            String family = null;
            for (Long candidateId : entry.getValue()) {
                String next = familyByCandidate.get(candidateId);
                if (next != null && !next.isBlank()) {
                    family = next;
                    break;
                }
            }
            if (family != null) {
                out.put(entry.getKey(), family.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
