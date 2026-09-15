package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persists Layer B line peers and mirrors resolved pairs on both bindings. */
final class BindingPeerWriter {

    record PendingPeerLine(long cellId, long worksheetId, String path, List<LinePeerRef> peers) {}

    private BindingPeerWriter() {}

    static List<BindingPeer> buildPeers(
            WorkspaceRepository repo,
            long parseRunId,
            List<NomenclatureBinding> bindings,
            List<PendingPeerLine> pending)
            throws SQLException, PeerCoordResolver.PeerCoordException {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<WorksheetRef> worksheets = repo.selectWorksheetsForParseRun(parseRunId);
        Map<Long, Map<String, Long>> cellIndex = PeerCoordResolver.indexCells(
                worksheets,
                worksheetId -> repo.selectCellsForWorksheet(worksheetId).stream()
                        .map(ref -> new PeerCoordResolver.PeerCellRef(ref.cellId(), ref.coord()))
                        .toList());
        Map<Long, String> pathByCell = new HashMap<>();
        for (NomenclatureBinding binding : bindings) {
            pathByCell.put(binding.cellId(), binding.path());
        }
        Set<String> seen = new LinkedHashSet<>();
        List<BindingPeer> rows = new ArrayList<>();
        for (PendingPeerLine line : pending) {
            if (line.peers() == null || line.peers().isEmpty()) {
                continue;
            }
            for (LinePeerRef peer : line.peers()) {
                if (!PeerReason.isKnown(peer.peerReason())) {
                    continue;
                }
                long sourceCell = line.cellId();
                var resolved = PeerCoordResolver.resolveCellId(
                        worksheets, cellIndex, line.worksheetId(), peer.peerCoord());
                if (resolved.isEmpty()) {
                    continue;
                }
                long peerCell = resolved.get();
                boolean pathResolved = pathsMatch(line.path(), pathByCell.get(peerCell));
                addPeer(rows, seen, parseRunId, sourceCell, peerCell, peer.peerReason(), pathResolved);
                if (pathResolved) {
                    addPeer(rows, seen, parseRunId, peerCell, sourceCell, peer.peerReason(), true);
                }
            }
        }
        return List.copyOf(rows);
    }

    private static boolean pathsMatch(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equals(right.trim());
    }

    private static void addPeer(
            List<BindingPeer> rows,
            Set<String> seen,
            long parseRunId,
            long cellId,
            long peerCellId,
            String reason,
            boolean pathResolved) {
        if (cellId == peerCellId) {
            return;
        }
        String key = cellId + "\0" + peerCellId + "\0" + reason;
        if (!seen.add(key)) {
            return;
        }
        rows.add(new BindingPeer(parseRunId, cellId, peerCellId, reason, pathResolved));
    }
}
