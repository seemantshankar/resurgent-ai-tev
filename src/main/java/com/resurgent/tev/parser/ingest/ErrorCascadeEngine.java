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
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.resurgent.tev.parser.db.WorkspaceRepository;

/**
 * Traces error propagation cascades across cell references to identify root error cells (barriers),
 * stops propagation at the error barrier functions of §10.8 (IFERROR/IFNA/ISERROR/ISNA/ISERR/COUNT/COUNTA/AGGREGATE/SUBTOTAL), and populates
 * {@code cell_error_root} mappings, {@code cell.error_root_cell_id}, and {@code cell.error_descendant}.
 */
public final class ErrorCascadeEngine {

    private static final Set<String> ERROR_BARRIERS = Set.of(
            "IFERROR", "IFNA", "ISERROR", "ISNA", "ISERR", "COUNT", "COUNTA", "AGGREGATE", "SUBTOTAL");

    /** Matches a barrier name only where it is used as a function call, not as a substring of a longer name. */
    private static final Pattern BARRIER_CALL = Pattern.compile(
            "(?<![A-Z0-9_.])(" + String.join("|", new TreeSet<>(ERROR_BARRIERS)) + ")\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private final WorkspaceRepository repo;

    public ErrorCascadeEngine(WorkspaceRepository repo) {
        this.repo = repo;
    }

    public void processErrorCascades(long parseRunId) throws SQLException, IOException {
        Set<Long> errorCellIds = repo.findErrorCellIdsByParseRun(parseRunId);
        if (errorCellIds.isEmpty()) {
            return;
        }

        Map<Long, String> formulaMap = repo.findFormulasByParseRun(parseRunId);

        // Map: precedent cell -> list of dependent cells (forward graph)
        Map<Long, List<Long>> dependentMap = new HashMap<>();
        // Map: dependent cell -> list of precedent cells (backward graph)
        Map<Long, List<Long>> precedentMap = new HashMap<>();

        try (ResultSet rs = repo.findCellReferencesByParseRun(parseRunId)) {
            while (rs.next()) {
                long fromId = rs.getLong("from_cell_id");
                long toId = rs.getLong("resolved_cell_id");
                if (!rs.wasNull()) {
                    dependentMap.computeIfAbsent(toId, k -> new ArrayList<>()).add(fromId);
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

        // Propagate forward from each root error cell, stopping at error barrier functions.
        // Roots are walked in ascending id order so cell.error_root_cell_id is the lowest root
        // reaching the cell, keeping re-ingest of the same file byte-identical.
        Set<Long> scalarRootAssigned = new HashSet<>();
        for (long rootId : new TreeSet<>(rootErrorCellIds)) {
            Queue<Long> queue = new LinkedList<>();
            Set<Long> visited = new HashSet<>();

            queue.add(rootId);
            visited.add(rootId);

            while (!queue.isEmpty()) {
                long current = queue.poll();

                // If current cell is NOT the root and contains an error barrier function, stop propagating along current
                if (current != rootId && isErrorBarrier(formulaMap.get(current))) {
                    continue;
                }

                repo.insertCellErrorRoot(current, rootId);
                if (scalarRootAssigned.add(current)) {
                    repo.updateCellErrorRoot(current, rootId);
                }

                if (current != rootId && !errorCellIds.contains(current)) {
                    repo.updateCellErrorDescendant(current, true);
                }

                List<Long> dependents = dependentMap.getOrDefault(current, List.of());
                for (long depId : dependents) {
                    if (visited.add(depId)) {
                        queue.add(depId);
                    }
                }
            }
        }
    }

    private static boolean isErrorBarrier(String formula) {
        if (formula == null || formula.isBlank()) {
            return false;
        }
        return BARRIER_CALL.matcher(formula).find();
    }
}
