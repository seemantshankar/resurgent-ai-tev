package com.resurgent.tev.parser.discover;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.CellPacketView;
import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.PersistedCellReference;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds Packets on demand from a Candidate plus the cell graph. Package-private.
 */
final class PacketBuilder {

    static final int INLINE_FORMULA_CELL_CAP = 64;

    Packet build(WorkspaceRepository repo, long candidateId)
            throws SQLException, DiscoverException {
        CandidateRow candidate = repo.selectCandidate(candidateId);
        if (candidate == null) {
            throw new DiscoverException("candidate not found: " + candidateId);
        }
        List<Long> memberIds = repo.selectCandidateMemberCellIds(candidateId);
        Set<Long> memberSet = new HashSet<>(memberIds);
        Map<Long, PacketCell> cells = new LinkedHashMap<>();
        List<PacketRangeRef> largeRanges = new ArrayList<>();

        for (CellPacketView view : repo.selectCellPacketViews(memberIds)) {
            cells.put(view.cellId(), toPacketCell(view, PacketCell.ROLE_CORE));
        }

        boolean contextClosure = appendInheritedContext(repo, candidate, memberSet, cells);
        appendFormulaContext(repo, candidate, memberSet, cells, largeRanges);

        return new Packet(
                candidate.candidateId(),
                candidate.parseRunId(),
                candidate.worksheetId(),
                candidate.candidateKind(),
                List.copyOf(cells.values()),
                List.copyOf(largeRanges),
                contextClosure);
    }

    /**
     * Default Packet selection: every non-coverage Candidate; coverage parent only when it is
     * the sole Candidate on that worksheet or a child fails context closure.
     */
    List<Packet> selectDefault(WorkspaceRepository repo, long parseRunId)
            throws SQLException, DiscoverException {
        List<CandidateRow> all = repo.selectCandidatesForParseRun(parseRunId);
        Map<Long, List<CandidateRow>> byWorksheet = new LinkedHashMap<>();
        for (CandidateRow candidate : all) {
            byWorksheet
                    .computeIfAbsent(candidate.worksheetId(), id -> new ArrayList<>())
                    .add(candidate);
        }
        List<Packet> packets = new ArrayList<>();
        for (List<CandidateRow> sheetCandidates : byWorksheet.values()) {
            List<CandidateRow> narrower = sheetCandidates.stream()
                    .filter(c -> !"coverage_parent".equals(c.candidateKind()))
                    .toList();
            CandidateRow coverage = sheetCandidates.stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .findFirst()
                    .orElse(null);

            boolean anyChildFailsClosure = false;
            for (CandidateRow narrow : narrower) {
                Packet packet = build(repo, narrow.candidateId());
                packets.add(packet);
                if (!packet.contextClosureSucceeded()) {
                    anyChildFailsClosure = true;
                }
            }
            if (coverage != null
                    && (narrower.isEmpty() || anyChildFailsClosure)) {
                packets.add(build(repo, coverage.candidateId()));
            }
        }
        return packets;
    }

    private static boolean appendInheritedContext(
            WorkspaceRepository repo,
            CandidateRow candidate,
            Set<Long> memberSet,
            Map<Long, PacketCell> cells)
            throws SQLException {
        if ("coverage_parent".equals(candidate.candidateKind())) {
            return true;
        }
        Integer minRow = candidate.bboxMinRow();
        Integer minCol = candidate.bboxMinCol();
        Integer maxCol = candidate.bboxMaxCol();
        if (minRow == null || minCol == null || maxCol == null) {
            return false;
        }

        Long parentId = candidate.parentCandidateId();
        List<CellPacketView> pool;
        if (parentId != null) {
            pool = repo.selectCellPacketViews(repo.selectCandidateMemberCellIds(parentId));
        } else {
            pool = repo.selectCellPacketViewsForWorksheet(candidate.worksheetId());
        }

        boolean found = false;
        for (CellPacketView view : pool) {
            if (memberSet.contains(view.cellId()) || cells.containsKey(view.cellId())) {
                continue;
            }
            // Shared-axis / header rows above the child within overlapping columns
            boolean headerRow = view.rowNum() < minRow
                    && view.colNum() >= minCol
                    && view.colNum() <= maxCol;
            // Label column to the left on the child's rows
            boolean labelCol = candidate.bboxMaxRow() != null
                    && view.colNum() < minCol
                    && view.rowNum() >= minRow
                    && view.rowNum() <= candidate.bboxMaxRow();
            if (headerRow || labelCol) {
                cells.put(view.cellId(), toPacketCell(view, PacketCell.ROLE_CONTEXT));
                found = true;
            }
        }
        return found || memberSet.size() <= 2;
    }

    private static void appendFormulaContext(
            WorkspaceRepository repo,
            CandidateRow candidate,
            Set<Long> memberSet,
            Map<Long, PacketCell> cells,
            List<PacketRangeRef> largeRanges)
            throws SQLException {
        List<PersistedCellReference> edges =
                repo.selectPersistedCellReferencesForParseRun(candidate.parseRunId());
        for (PersistedCellReference persisted : edges) {
            CellReferenceEdge edge = persisted.edge();
            if (!memberSet.contains(edge.fromCellId())) {
                continue;
            }
            if (edge.resolvedCellId() != null) {
                addContextCell(repo, edge.resolvedCellId(), cells);
                continue;
            }
            Long targetWs = edge.targetWorksheetId();
            String range = edge.targetRange();
            if (targetWs == null || range == null) {
                continue;
            }
            // Other-sheet context only via reference edge (always true here).
            List<CellPacketView> targets = repo.selectCellsInTargetRange(targetWs, range);
            if (targets.size() > INLINE_FORMULA_CELL_CAP) {
                largeRanges.add(new PacketRangeRef(
                        edge.fromCellId(),
                        persisted.cellReferenceId(),
                        range,
                        targetWs,
                        targets.size()));
            } else {
                for (CellPacketView target : targets) {
                    if (!cells.containsKey(target.cellId())) {
                        cells.put(target.cellId(), toPacketCell(target, PacketCell.ROLE_CONTEXT));
                    }
                }
            }
        }
    }

    private static void addContextCell(
            WorkspaceRepository repo, long cellId, Map<Long, PacketCell> cells)
            throws SQLException {
        if (cells.containsKey(cellId)) {
            return;
        }
        List<CellPacketView> views = repo.selectCellPacketViews(List.of(cellId));
        if (!views.isEmpty()) {
            cells.put(cellId, toPacketCell(views.get(0), PacketCell.ROLE_CONTEXT));
        }
    }

    private static PacketCell toPacketCell(CellPacketView view, String role) {
        return new PacketCell(
                view.cellId(),
                view.worksheetId(),
                view.coord(),
                view.rowNum(),
                view.colNum(),
                role,
                view.valueType(),
                view.textValue(),
                view.displayValue(),
                view.numericValue(),
                view.formulaText(),
                view.rowHidden(),
                view.colHidden());
    }
}
