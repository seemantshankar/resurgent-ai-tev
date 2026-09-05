package com.resurgent.tev.parser.discover;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.CellPacketView;
import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.PersistedCellReference;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records Candidate relationships when a persisted formula-reference edge links their members.
 * Does not merge members across Candidates or worksheets. Package-private.
 */
final class RelatedCandidateLinker {

    static final String RELATIONSHIP_KIND = "formula_reference";

    void link(WorkspaceRepository repo, long parseRunId) throws SQLException {
        List<CandidateRow> candidates = repo.selectCandidatesForParseRun(parseRunId);
        if (candidates.size() < 2) {
            return;
        }

        Map<Long, List<CandidateRow>> byCell = indexCandidatesByMember(repo, candidates);
        List<PersistedCellReference> edges = repo.selectPersistedCellReferencesForParseRun(parseRunId);
        Set<String> seen = new HashSet<>();

        for (PersistedCellReference persisted : edges) {
            CellReferenceEdge edge = persisted.edge();
            Long targetCellId = edge.resolvedCellId();
            if (targetCellId == null
                    && edge.targetWorksheetId() != null
                    && edge.targetRange() != null) {
                List<CellPacketView> targets =
                        repo.selectCellsInTargetRange(edge.targetWorksheetId(), edge.targetRange());
                if (targets.isEmpty()) {
                    continue;
                }
                targetCellId = targets.get(0).cellId();
            }
            if (targetCellId == null) {
                continue;
            }
            CandidateRow from = pickCandidate(byCell.get(edge.fromCellId()));
            CandidateRow to = pickCandidate(byCell.get(targetCellId));
            if (from == null || to == null || from.candidateId() == to.candidateId()) {
                continue;
            }
            String key = from.candidateId() < to.candidateId()
                    ? from.candidateId() + ":" + to.candidateId()
                    : to.candidateId() + ":" + from.candidateId();
            if (!seen.add(key)) {
                continue;
            }
            repo.insertCandidateRelated(from.candidateId(), to.candidateId(), RELATIONSHIP_KIND);
        }
    }

    private static Map<Long, List<CandidateRow>> indexCandidatesByMember(
            WorkspaceRepository repo, List<CandidateRow> candidates) throws SQLException {
        Map<Long, List<CandidateRow>> byCell = new HashMap<>();
        for (CandidateRow candidate : candidates) {
            for (Long cellId : repo.selectCandidateMemberCellIds(candidate.candidateId())) {
                byCell.computeIfAbsent(cellId, id -> new ArrayList<>()).add(candidate);
            }
        }
        return byCell;
    }

    /** Prefer the narrowest non-coverage Candidate that owns the cell. */
    private static CandidateRow pickCandidate(List<CandidateRow> owners) {
        if (owners == null || owners.isEmpty()) {
            return null;
        }
        return owners.stream()
                .min(Comparator
                        .comparing((CandidateRow c) -> "coverage_parent".equals(c.candidateKind()))
                        .thenComparingInt(c -> {
                            Integer minR = c.bboxMinRow();
                            Integer maxR = c.bboxMaxRow();
                            Integer minC = c.bboxMinCol();
                            Integer maxC = c.bboxMaxCol();
                            if (minR == null || maxR == null || minC == null || maxC == null) {
                                return Integer.MAX_VALUE;
                            }
                            return (maxR - minR + 1) * (maxC - minC + 1);
                        })
                        .thenComparingLong(CandidateRow::candidateId))
                .orElse(null);
    }
}
