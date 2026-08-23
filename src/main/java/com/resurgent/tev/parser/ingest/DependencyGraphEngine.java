package com.resurgent.tev.parser.ingest;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import com.resurgent.tev.parser.db.Jsonb;
import com.resurgent.tev.parser.db.WorkspaceRepository;

/**
 * Detects cycles in a pre-built cell-reference adjacency graph (see {@link ReferenceGraphLoader})
 * via Tarjan's SCC algorithm, tags circular cells with deterministic group IDs, queues a
 * review row per cycle, and updates workbook calculation metadata.
 */
public final class DependencyGraphEngine {

    private final WorkspaceRepository repo;

    public DependencyGraphEngine(WorkspaceRepository repo) {
        this.repo = repo;
    }

    /**
     * @param iterativeCalc whether the workbook has {@code iterate=1} in its calcPr — cycle
     *                      severity is {@code "info"} when true (iterative calc means the
     *                      author expects and tolerates circularity), {@code "warning"}
     *                      otherwise. Either way the run never fails on a cycle alone.
     */
    public void processWorkbookGraph(long workbookId, long parseRunId, Map<Long, List<Long>> adjacency,
            boolean iterativeCalc) throws SQLException, IOException {
        TarjanScc sccFinder = new TarjanScc(adjacency);
        List<List<Long>> cycles = sccFinder.findCycles();

        boolean isCircular = !cycles.isEmpty();
        int groupCount = cycles.size();
        int maxCycleLength = 0;
        String severity = iterativeCalc ? "info" : "warning";
        String now = com.resurgent.tev.parser.db.Timestamps.now();

        for (List<Long> cycle : cycles) {
            maxCycleLength = Math.max(maxCycleLength, cycle.size());
            long groupId = Collections.min(cycle);
            for (long cellId : cycle) {
                repo.updateCellCircularStatus(cellId, true, groupId);
            }
            // review_queue has no dedicated severity column, so severity (info when the
            // workbook has iterative_calc enabled, warning otherwise — a cycle never fails
            // the run on its own) travels in the detail JSON alongside the cycle's cells.
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("circularGroupId", groupId);
            detail.put("cellIds", cycle);
            detail.put("severity", severity);
            repo.insertReviewQueue(parseRunId, "circular_reference",
                    "Circular reference detected (" + cycle.size() + " cells)",
                    Jsonb.toJson(detail), "Pending", false, now, null);
        }

        repo.updateWorkbookCycleMetadata(workbookId, isCircular, groupCount, maxCycleLength);
    }

    private static final class TarjanScc {
        private final Map<Long, List<Long>> graph;
        private int index = 0;
        private final Map<Long, Integer> indices = new HashMap<>();
        private final Map<Long, Integer> lowLink = new HashMap<>();
        private final Stack<Long> stack = new Stack<>();
        private final Set<Long> onStack = new HashSet<>();
        private final List<List<Long>> sccs = new ArrayList<>();

        TarjanScc(Map<Long, List<Long>> graph) {
            this.graph = graph;
        }

        List<List<Long>> findCycles() {
            for (Long node : graph.keySet()) {
                if (!indices.containsKey(node)) {
                    strongConnect(node);
                }
            }

            List<List<Long>> cycles = new ArrayList<>();
            for (List<Long> scc : sccs) {
                if (scc.size() > 1) {
                    cycles.add(scc);
                } else if (scc.size() == 1) {
                    Long singleNode = scc.get(0);
                    List<Long> neighbors = graph.get(singleNode);
                    if (neighbors != null && neighbors.contains(singleNode)) {
                        cycles.add(scc);
                    }
                }
            }
            return cycles;
        }

        private void strongConnect(Long node) {
            indices.put(node, index);
            lowLink.put(node, index);
            index++;
            stack.push(node);
            onStack.add(node);

            List<Long> neighbors = graph.getOrDefault(node, List.of());
            for (Long neighbor : neighbors) {
                if (!indices.containsKey(neighbor)) {
                    strongConnect(neighbor);
                    lowLink.put(node, Math.min(lowLink.get(node), lowLink.get(neighbor)));
                } else if (onStack.contains(neighbor)) {
                    lowLink.put(node, Math.min(lowLink.get(node), indices.get(neighbor)));
                }
            }

            if (lowLink.get(node).equals(indices.get(node))) {
                List<Long> scc = new ArrayList<>();
                Long w;
                do {
                    w = stack.pop();
                    onStack.remove(w);
                    scc.add(w);
                } while (!w.equals(node));
                sccs.add(scc);
            }
        }
    }
}
