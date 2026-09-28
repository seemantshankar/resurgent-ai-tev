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

    static void write(WorkspaceRepository repo, long parseRunId, List<CandidateRow> candidates)
            throws SQLException {
        if (candidates.isEmpty()) {
            return;
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
        for (InterpretationCellView cell : cells) {
            if (!eligible.contains(cell.cellId())) {
                continue;
            }
            NomenclatureBinding binding = bindingsByCell.get(cell.cellId());
            String status = binding != null ? "bound" : "not_applicable";
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
            }
        }
    }
}
