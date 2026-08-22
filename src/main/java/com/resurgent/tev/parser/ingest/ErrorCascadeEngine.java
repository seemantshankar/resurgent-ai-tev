package com.resurgent.tev.parser.ingest;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.resurgent.tev.parser.db.WorkspaceRepository;

/**
 * Traces error propagation cascades across cell references to identify root error cells (barriers)
 * and populates {@code cell_error_root} mappings and {@code cell.error_root_cell_id}.
 */
public final class ErrorCascadeEngine {

    private final WorkspaceRepository repo;

    public ErrorCascadeEngine(WorkspaceRepository repo) {
        this.repo = repo;
    }

    public void processErrorCascades(long parseRunId) throws SQLException, IOException {
        Set<Long> errorCellIds = repo.findErrorCellIdsByParseRun(parseRunId);
        if (errorCellIds.isEmpty()) {
            return;
        }

        // Map: dependent cell -> list of precedent cells referenced
        Map<Long, List<Long>> precedentMap = new HashMap<>();
        try (ResultSet rs = repo.findCellReferencesByParseRun(parseRunId)) {
            while (rs.next()) {
                long fromId = rs.getLong("from_cell_id");
                long toId = rs.getLong("resolved_cell_id");
                if (!rs.wasNull()) {
                    precedentMap.computeIfAbsent(fromId, k -> new ArrayList<>()).add(toId);
                }
            }
        }

        // Identify root error cells: error cells whose precedents contain no error cells
        Set<Long> rootErrorCellIds = new HashSet<>();
        for (long cellId : errorCellIds) {
            List<Long> precedents = precedentMap.getOrDefault(cellId, List.of());
            boolean hasErrorPrecedent = false;
            for (long prec : precedents) {
                if (errorCellIds.contains(prec)) {
                    hasErrorPrecedent = true;
                    break;
                }
            }
            if (!hasErrorPrecedent) {
                rootErrorCellIds.add(cellId);
            }
        }

        // For each error cell, trace back to find all root error cells it depends on
        for (long cellId : errorCellIds) {
            Set<Long> rootsForCell = findRootErrors(cellId, precedentMap, rootErrorCellIds);
            Long primaryRoot = null;
            for (long rootId : rootsForCell) {
                repo.insertCellErrorRoot(cellId, rootId);
                if (primaryRoot == null || rootId < primaryRoot) {
                    primaryRoot = rootId;
                }
            }
            if (primaryRoot != null) {
                repo.updateCellErrorRoot(cellId, primaryRoot);
            }
        }
    }

    private static Set<Long> findRootErrors(long startCellId, Map<Long, List<Long>> precedentMap, Set<Long> rootErrorCellIds) {
        Set<Long> roots = new HashSet<>();
        if (rootErrorCellIds.contains(startCellId)) {
            roots.add(startCellId);
            return roots;
        }

        Set<Long> visited = new HashSet<>();
        Queue<Long> queue = new LinkedList<>();
        queue.add(startCellId);
        visited.add(startCellId);

        while (!queue.isEmpty()) {
            long current = queue.poll();
            List<Long> precedents = precedentMap.getOrDefault(current, List.of());
            for (long prec : precedents) {
                if (rootErrorCellIds.contains(prec)) {
                    roots.add(prec);
                } else if (visited.add(prec)) {
                    queue.add(prec);
                }
            }
        }

        return roots;
    }
}
