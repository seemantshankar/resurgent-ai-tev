package com.resurgent.tev.parser.ingest;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.resurgent.tev.parser.db.Jsonb;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.Timestamps;

/**
 * Traces error propagation cascades across a pre-built cell-reference adjacency graph
 * (see {@link ReferenceGraphLoader}) to identify root error cells and populate
 * {@code cell_error_root} / {@code cell.error_root_cell_id} (existing cells-that-are-errors
 * behavior) plus {@code cell.error_descendant} (CONTEXT.md: "a cell whose evaluation chain
 * passes through an error root but is not itself an error" — this was previously dead
 * DDL because nothing ever walked forward from a root).
 *
 * <p>Propagation stops at error barrier cells (§10.8: IFERROR/IFNA/ISERROR/ISNA/ISERR/
 * COUNT/COUNTA/AGGREGATE/SUBTOTAL) unless the barrier cell is itself flagged {@code is_error},
 * in which case it did not actually consume the error and participates as an ordinary
 * error/root cell.
 */
public final class ErrorCascadeEngine {

    private final WorkspaceRepository repo;

    public ErrorCascadeEngine(WorkspaceRepository repo) {
        this.repo = repo;
    }

    public void processErrorCascades(long parseRunId, Map<Long, List<Long>> adjacency) throws SQLException, IOException {
        Set<Long> errorCellIds = repo.findErrorCellIdsByParseRun(parseRunId);
        Map<Long, Boolean> barrierIsError = repo.findErrorBarrierCellsByParseRun(parseRunId);
        String now = Timestamps.now();

        // Barrier-vs-cache disagreement: a barrier cell (function-wise) whose own cached
        // value still shows an error means the error-consuming function didn't actually
        // consume it — flag for review rather than silently swallowing the disagreement.
        for (Map.Entry<Long, Boolean> entry : barrierIsError.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("cellId", entry.getKey());
                repo.insertReviewQueue(parseRunId, "error_barrier_disagreement",
                        "Cell wraps an error-consuming function but is still flagged is_error",
                        Jsonb.toJson(detail), "Pending", false, now, null);
            }
        }

        if (errorCellIds.isEmpty()) {
            return;
        }

        // Reverse adjacency: precedent -> cells that directly reference it (dependents).
        Map<Long, List<Long>> reverseAdjacency = new HashMap<>();
        for (Map.Entry<Long, List<Long>> entry : adjacency.entrySet()) {
            long from = entry.getKey();
            for (long to : entry.getValue()) {
                reverseAdjacency.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
            }
        }

        // Root error cells: error cells with no error precedent.
        Set<Long> rootErrorCellIds = new HashSet<>();
        for (long cellId : errorCellIds) {
            List<Long> precedents = adjacency.getOrDefault(cellId, List.of());
            boolean hasErrorPrecedent = precedents.stream().anyMatch(errorCellIds::contains);
            if (!hasErrorPrecedent) {
                rootErrorCellIds.add(cellId);
            }
        }

        // Existing behavior: for each error cell, trace back to the root error(s) it depends on.
        for (long cellId : errorCellIds) {
            Set<Long> rootsForCell = findRootErrors(cellId, adjacency, rootErrorCellIds);
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

        // New (C2): forward traversal from each root, marking non-error descendants and
        // stopping at barrier cells that actually consumed the error.
        for (long root : rootErrorCellIds) {
            Set<Long> visited = new HashSet<>();
            visited.add(root);
            Deque<Long> queue = new ArrayDeque<>(reverseAdjacency.getOrDefault(root, List.of()));
            while (!queue.isEmpty()) {
                long current = queue.poll();
                if (!visited.add(current)) {
                    continue;
                }

                boolean isBarrier = barrierIsError.containsKey(current);
                boolean barrierConsumed = isBarrier && !Boolean.TRUE.equals(barrierIsError.get(current));

                if (barrierConsumed) {
                    // Consumed the error: not itself a descendant of this root, and
                    // propagation does not cross past it.
                    continue;
                }

                if (!errorCellIds.contains(current)) {
                    repo.updateCellErrorDescendant(current, true);
                    repo.insertCellErrorRoot(current, root);
                }
                // errorCellIds cells were already handled above (cell_error_root / scalar);
                // still continue traversing past them so further-downstream non-error cells
                // are reached.
                for (long next : reverseAdjacency.getOrDefault(current, List.of())) {
                    if (!visited.contains(next)) {
                        queue.add(next);
                    }
                }
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
        Deque<Long> queue = new ArrayDeque<>();
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
