package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.NomenclatureBinding;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the existing header resolver for main and helper cells and stores the
 * evidence. Cells with no row or column header keep a missing evidence row.
 */
final class HeaderBindingWriter {

    private HeaderBindingWriter() {}

    /** Cells and evidence rows written by the last {@link #write}; for the run log and stats. */
    record Written(int cells, int evidenceRows) {}

    /**
     * "bound" has a path; "not_applicable" is an error cell or one owned by a scratch region;
     * anything else is "unbound" (read, but no nomenclature path yet or found).
     */
    static String status(NomenclatureBinding binding, InterpretationCellView cell, List<CandidateRow> owners) {
        if (binding != null) {
            return "bound";
        }
        if (cell.isError()) {
            return "not_applicable";
        }
        if (owners != null) {
            for (CandidateRow owner : owners) {
                if ("scratch".equals(owner.structuralRole())) {
                    return "not_applicable";
                }
            }
        }
        return "unbound";
    }

    static Written write(WorkspaceRepository repo, long parseRunId, List<CandidateRow> candidates)
            throws SQLException {
        int evidenceRows = 0;
        if (candidates.isEmpty()) {
            return new Written(0, 0);
        }
        List<InterpretationCellView> cells = repo.selectInterpretationCellsForParseRun(parseRunId);
        Map<Long, InterpretationCellView> byId = InterpretationEvidenceResolver.indexCells(cells);
        List<CandidateRow> allCandidates = repo.selectCandidatesForParseRun(parseRunId);
        Map<Long, Set<Long>> membersByCandidate =
                InterpretationEvidenceResolver.indexMembers(repo.selectCandidateMembersForParseRun(parseRunId));
        Map<Long, List<CandidateRow>> ownersByCell =
                InterpretationEvidenceResolver.indexOwners(allCandidates, membersByCandidate);
        Map<Long, CandidateRow> candidatesById = new HashMap<>();
        for (CandidateRow candidate : allCandidates) {
            candidatesById.put(candidate.candidateId(), candidate);
        }
        Map<Long, NomenclatureBinding> bindingsByCell = new HashMap<>();
        for (NomenclatureBinding binding : repo.selectNomenclatureBindings(parseRunId)) {
            bindingsByCell.put(binding.cellId(), binding);
        }
        Set<Long> eligible = new HashSet<>();
        for (CandidateRow candidate : candidates) {
            eligible.addAll(membersByCandidate.getOrDefault(candidate.candidateId(), Set.of()));
            repo.deleteInterpretationsForCells(parseRunId, candidate.candidateId());
        }
        InterpretationEvidenceResolver.ResolveCache cache =
                new InterpretationEvidenceResolver.ResolveCache(byId);
        cache.useGeometry(repo.selectHeaderGeometry(parseRunId));
        int done = 0;
        for (InterpretationCellView cell : cells) {
            if (!eligible.contains(cell.cellId())) {
                continue;
            }
            com.resurgent.tev.parser.Progress.step("labels", "cells with row/column labels saved",
                    ++done, eligible.size(), 2500);
            NomenclatureBinding binding = bindingsByCell.get(cell.cellId());
            String status = status(binding, cell, ownersByCell.get(cell.cellId()));
            repo.insertCellInterpretation(
                    parseRunId,
                    cell,
                    binding == null ? null : binding.path(),
                    binding == null ? null : binding.amountRole(),
                    status);
            List<InterpretationEvidence> evidence = InterpretationEvidenceResolver.resolve(
                    parseRunId,
                    cell,
                    cache,
                    ownersByCell,
                    membersByCandidate,
                    candidatesById,
                    binding);
            for (InterpretationEvidence item : evidence) {
                repo.insertInterpretationEvidence(item);
                evidenceRows++;
            }
        }
        return new Written(done, evidenceRows);
    }
}
