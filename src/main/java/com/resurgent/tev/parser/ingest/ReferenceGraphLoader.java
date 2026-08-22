package com.resurgent.tev.parser.ingest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.resurgent.tev.parser.db.WorkspaceRepository;

/**
 * Builds the forward cell-reference adjacency map (precedent-referencing-cell -&gt;
 * referenced cells) for a parse run, expanding range references (§4.4) in memory —
 * clamped to each worksheet's real bbox, never fabricating cells for blank
 * coordinates. Ranges are stored unexpanded in {@code cell_reference}; this loader
 * is the single place both {@link DependencyGraphEngine} and {@link ErrorCascadeEngine}
 * get their graph edges from, so the expansion logic isn't duplicated between them.
 */
public final class ReferenceGraphLoader {

    private static final Pattern CELL_PATTERN = Pattern.compile("^\\$?([A-Z]+)\\$?(\\d+)$");

    private ReferenceGraphLoader() {}

    public static Map<Long, List<Long>> loadAdjacency(WorkspaceRepository repo, long parseRunId)
            throws SQLException {
        Map<Long, List<Long>> adjacency = new HashMap<>();
        Map<Long, int[]> bboxCache = new HashMap<>();

        try (ResultSet rs = repo.findCellReferenceRowsByParseRun(parseRunId)) {
            while (rs.next()) {
                long fromId = rs.getLong("from_cell_id");
                long resolvedId = rs.getLong("resolved_cell_id");
                boolean hasResolved = !rs.wasNull();
                long targetWorksheetId = rs.getLong("target_worksheet_id");
                boolean hasTargetWorksheet = !rs.wasNull();
                String targetRange = rs.getString("target_range");
                boolean isWholeColumn = rs.getInt("is_whole_column") == 1;
                boolean isWholeRow = rs.getInt("is_whole_row") == 1;
                long fromWorksheetId = rs.getLong("from_worksheet_id");

                if (hasResolved) {
                    addEdge(adjacency, fromId, resolvedId);
                    continue;
                }

                boolean isRange = (targetRange != null && targetRange.contains(":")) || isWholeColumn || isWholeRow;
                if (!isRange) {
                    continue;
                }

                long worksheetId = hasTargetWorksheet ? targetWorksheetId : fromWorksheetId;
                int[] bbox = bboxCache.computeIfAbsent(worksheetId, id -> {
                    try {
                        return repo.findWorksheetBbox(id);
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
                if (bbox == null) {
                    continue;
                }

                int[] bounds = resolveBounds(targetRange, isWholeColumn, isWholeRow, bbox);
                if (bounds == null) {
                    continue;
                }
                List<Long> targets = repo.findCellIdsInRange(worksheetId,
                        bounds[0], bounds[1], bounds[2], bounds[3]);
                for (long targetId : targets) {
                    if (targetId != fromId) {
                        addEdge(adjacency, fromId, targetId);
                    }
                }
            }
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException sqle) {
                throw sqle;
            }
            throw e;
        }

        return adjacency;
    }

    /**
     * Returns {minRow, maxRow, minCol, maxCol} for a range token, clamped to the
     * worksheet's real bbox. {@code isWholeColumn}/{@code isWholeRow} tokens already carry
     * Excel's full-column/full-row bounds (e.g. A1:A65536) baked into {@code targetRange}
     * by the tokenizer, so clamping against the bbox here is what keeps a whole-column
     * reference like {@code =SUM(A:A)} from expanding past the worksheet's real content.
     */
    private static int[] resolveBounds(String targetRange, boolean isWholeColumn, boolean isWholeRow, int[] bbox) {
        int bboxMinRow = bbox[0];
        int bboxMinCol = bbox[1];
        int bboxMaxRow = bbox[2];
        int bboxMaxCol = bbox[3];

        if (targetRange == null || !targetRange.contains(":")) {
            return null;
        }
        String[] parts = targetRange.split(":", 2);
        int[] first = parseCell(parts[0]);
        int[] second = parseCell(parts[1]);
        if (first == null || second == null) {
            return null;
        }
        int minRow = Math.max(bboxMinRow, Math.min(first[0], second[0]));
        int maxRow = Math.min(bboxMaxRow, Math.max(first[0], second[0]));
        int minCol = Math.max(bboxMinCol, Math.min(first[1], second[1]));
        int maxCol = Math.min(bboxMaxCol, Math.max(first[1], second[1]));
        if (minRow > maxRow || minCol > maxCol) {
            return null;
        }
        return new int[] { minRow, maxRow, minCol, maxCol };
    }

    private static int[] parseCell(String cellRef) {
        Matcher m = CELL_PATTERN.matcher(cellRef.trim());
        if (!m.matches()) {
            return null;
        }
        int col = columnToNumber(m.group(1));
        int row = Integer.parseInt(m.group(2));
        return new int[] { row, col };
    }

    private static int columnToNumber(String col) {
        int result = 0;
        for (int i = 0; i < col.length(); i++) {
            result = result * 26 + (col.charAt(i) - 'A' + 1);
        }
        return result;
    }

    private static void addEdge(Map<Long, List<Long>> adjacency, long from, long to) {
        adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        adjacency.putIfAbsent(to, new ArrayList<>());
    }
}
