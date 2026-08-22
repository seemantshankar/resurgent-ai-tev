package com.resurgent.tev.parser.ingest;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import com.resurgent.tev.parser.db.WorkspaceRepository;

/**
 * Builds dependency graphs across cell references, detects cycles via Tarjan's SCC algorithm,
 * tags circular cells with deterministic group IDs, and updates workbook calculation metadata.
 */
public final class DependencyGraphEngine {

    private final WorkspaceRepository repo;

    public DependencyGraphEngine(WorkspaceRepository repo) {
        this.repo = repo;
    }

    public void processWorkbookGraph(long workbookId, long parseRunId) throws SQLException, IOException {
        Map<Long, List<Long>> adjacency = new HashMap<>();

        try (ResultSet rs = repo.findCellReferencesByParseRun(parseRunId)) {
            while (rs.next()) {
                long fromId = rs.getLong("from_cell_id");
                long toId = rs.getLong("resolved_cell_id");
                if (!rs.wasNull()) {
                    adjacency.computeIfAbsent(fromId, k -> new ArrayList<>()).add(toId);
                    adjacency.putIfAbsent(toId, new ArrayList<>());
                }
            }
        }

        TarjanScc sccFinder = new TarjanScc(adjacency);
        List<List<Long>> cycles = sccFinder.findCycles();

        boolean isCircular = !cycles.isEmpty();
        int groupCount = cycles.size();
        int maxCycleLength = 0;

        for (List<Long> cycle : cycles) {
            maxCycleLength = Math.max(maxCycleLength, cycle.size());
            long groupId = Collections.min(cycle);
            for (long cellId : cycle) {
                repo.updateCellCircularStatus(cellId, true, groupId);
            }
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
