package com.resurgent.tev.parser.discover;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.resurgent.tev.parser.db.CellEvidence;
import com.resurgent.tev.parser.db.Jsonb;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compact structural evidence JSON for persisted Candidates: internal whitespace,
 * anchors, and layout signatures. Coordinates only — never invents blank cell rows.
 */
final class CandidateStructuralEvidence {

    private CandidateStructuralEvidence() {}

    /**
     * Coordinates inside the bbox with no persisted cell, compacted as horizontal
     * runs {@code {"r":row,"c1":minCol,"c2":maxCol}}. Null when the bbox is empty
     * or fully occupied.
     */
    static String internalWhitespaceJson(
            Integer minRow, Integer minCol, Integer maxRow, Integer maxCol,
            Set<Long> occupiedPackedCoords) {
        if (minRow == null || minCol == null || maxRow == null || maxCol == null) {
            return null;
        }
        if (occupiedPackedCoords == null) {
            occupiedPackedCoords = Set.of();
        }
        List<Map<String, Integer>> ranges = new ArrayList<>();
        for (int r = minRow; r <= maxRow; r++) {
            Integer runStart = null;
            for (int c = minCol; c <= maxCol; c++) {
                boolean occupied = occupiedPackedCoords.contains(pack(r, c));
                if (!occupied) {
                    if (runStart == null) {
                        runStart = c;
                    }
                } else if (runStart != null) {
                    ranges.add(range(r, runStart, c - 1));
                    runStart = null;
                }
            }
            if (runStart != null) {
                ranges.add(range(r, runStart, maxCol));
            }
        }
        if (ranges.isEmpty()) {
            return null;
        }
        return toJson(ranges);
    }

    static Set<Long> occupiedPackedCoords(List<CellEvidence> cells) {
        Set<Long> out = new LinkedHashSet<>();
        for (CellEvidence cell : cells) {
            out.add(pack(cell.rowNum(), cell.colNum()));
        }
        return out;
    }

    /**
     * Structural anchors only: merge anchors, bold-styled cells, and text-heavy
     * header-like rows. No glossary or sheet-name rules.
     */
    static String anchorsJson(List<CellEvidence> cells) {
        if (cells == null || cells.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> anchors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (CellEvidence cell : cells) {
            if (cell.isMergedAnchor()) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("kind", "merge");
                a.put("r", cell.rowNum());
                a.put("c", cell.colNum());
                if (cell.mergedRange() != null) {
                    a.put("merged_range", cell.mergedRange());
                }
                String key = "merge:" + cell.rowNum() + ":" + cell.colNum();
                if (seen.add(key)) {
                    anchors.add(a);
                }
            }
            if (Boolean.TRUE.equals(cell.isBold())) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("kind", "bold");
                a.put("r", cell.rowNum());
                a.put("c", cell.colNum());
                String key = "bold:" + cell.rowNum() + ":" + cell.colNum();
                if (seen.add(key)) {
                    anchors.add(a);
                }
            }
        }

        Map<Integer, List<CellEvidence>> byRow = new TreeMap<>();
        for (CellEvidence cell : cells) {
            byRow.computeIfAbsent(cell.rowNum(), r -> new ArrayList<>()).add(cell);
        }
        for (Map.Entry<Integer, List<CellEvidence>> e : byRow.entrySet()) {
            List<CellEvidence> row = e.getValue();
            if (row.size() < 2) {
                continue;
            }
            long textish = row.stream().filter(c -> isTextish(c.valueType())).count();
            if (textish == row.size()) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("kind", "text_row");
                a.put("r", e.getKey());
                a.put("cols", row.stream().map(CellEvidence::colNum).sorted().toList());
                String key = "text_row:" + e.getKey();
                if (seen.add(key)) {
                    anchors.add(a);
                }
            }
        }

        if (anchors.isEmpty()) {
            return null;
        }
        return toJson(anchors);
    }

    /**
     * Compact occupancy + coarse value-type pattern for the Candidate's cells.
     */
    static String structuralSignaturesJson(List<CellEvidence> cells) {
        if (cells == null || cells.isEmpty()) {
            return null;
        }
        TreeSet<Integer> occupiedCols = new TreeSet<>();
        TreeMap<Integer, TreeMap<String, Integer>> typeByCol = new TreeMap<>();
        TreeSet<Integer> occupiedRows = new TreeSet<>();
        for (CellEvidence cell : cells) {
            occupiedCols.add(cell.colNum());
            occupiedRows.add(cell.rowNum());
            typeByCol.computeIfAbsent(cell.colNum(), c -> new TreeMap<>())
                    .merge(coarseType(cell.valueType()), 1, Integer::sum);
        }
        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("occupied_cols", new ArrayList<>(occupiedCols));
        sig.put("occupied_rows", List.of(
                occupiedRows.first(), occupiedRows.last()));
        Map<String, Object> types = new LinkedHashMap<>();
        for (Map.Entry<Integer, TreeMap<String, Integer>> e : typeByCol.entrySet()) {
            types.put(Integer.toString(e.getKey()), e.getValue());
        }
        sig.put("type_by_col", types);
        return toJson(sig);
    }

    static long pack(int row, int col) {
        return (((long) row) << 32) | (col & 0xffffffffL);
    }

    private static Map<String, Integer> range(int row, int c1, int c2) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("r", row);
        m.put("c1", c1);
        m.put("c2", c2);
        return m;
    }

    private static boolean isTextish(String valueType) {
        return "text".equals(valueType) || "quantity_text".equals(valueType);
    }

    private static String coarseType(String valueType) {
        if (valueType == null) {
            return "?";
        }
        return switch (valueType) {
            case "number", "bool", "date" -> "N";
            case "formula", "error" -> "F";
            case "text", "quantity_text" -> "T";
            default -> "O";
        };
    }

    private static String toJson(Object value) {
        try {
            return Jsonb.toJson(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("structural evidence JSON failed", e);
        }
    }
}
