package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where one region keeps its labels, as the LLM read it off the grid: the column-header bands
 * (a run of header rows and the columns those headers govern) and the columns that hold row
 * labels. Code assembles every cell's label text from this, so a wrong answer is one wrong
 * coordinate here and not thousands of scattered strings.
 */
public record HeaderGeometry(long candidateId, List<Band> bands, List<Integer> rowLabelColumns) {

    /** Header rows {@code rowMin..rowMax}, governing columns {@code colMin..colMax} (1-based). */
    public record Band(int rowMin, int rowMax, int colMin, int colMax) {
        public boolean coversColumn(int col) {
            return col >= colMin && col <= colMax;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public HeaderGeometry {
        bands = List.copyOf(bands);
        rowLabelColumns = List.copyOf(rowLabelColumns);
    }

    public boolean isEmpty() {
        return bands.isEmpty() && rowLabelColumns.isEmpty();
    }

    /** The bands nearest above {@code row} that govern {@code col}: all that share the closest bottom row. */
    public List<Band> bandsAbove(int row, int col) {
        int nearest = Integer.MIN_VALUE;
        for (Band band : bands) {
            if (band.rowMax() < row && band.coversColumn(col)) {
                nearest = Math.max(nearest, band.rowMax());
            }
        }
        List<Band> found = new ArrayList<>();
        for (Band band : bands) {
            if (band.rowMax() == nearest && band.coversColumn(col)) {
                found.add(band);
            }
        }
        return found;
    }

    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode bandNodes = root.putArray("bands");
        for (Band band : bands) {
            bandNodes.add(columnLetters(band.colMin()) + band.rowMin() + ":" + columnLetters(band.colMax()) + band.rowMax());
        }
        ArrayNode columns = root.putArray("rowLabelColumns");
        for (int col : rowLabelColumns) {
            columns.add(columnLetters(col));
        }
        return root.toString();
    }

    public static HeaderGeometry fromJson(long candidateId, String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<Band> bands = new ArrayList<>();
            for (JsonNode node : root.path("bands")) {
                Band band = parseBand(node.asText());
                if (band != null) {
                    bands.add(band);
                }
            }
            List<Integer> columns = new ArrayList<>();
            for (JsonNode node : root.path("rowLabelColumns")) {
                int col = columnNumber(node.asText());
                if (col > 0 && !columns.contains(col)) {
                    columns.add(col);
                }
            }
            columns.sort(Integer::compare);
            return new HeaderGeometry(candidateId, bands, columns);
        } catch (Exception e) {
            throw new IllegalArgumentException("unreadable header geometry: " + e.getMessage(), e);
        }
    }

    /** {@code "B4:G7"}, in any corner order; null when it is not a range. */
    static Band parseBand(String range) {
        if (range == null) {
            return null;
        }
        String[] parts = range.trim().replace("$", "").toUpperCase(Locale.ROOT).split(":");
        if (parts.length != 2) {
            return null;
        }
        int[] a = cellRef(parts[0]);
        int[] b = cellRef(parts[1]);
        if (a == null || b == null) {
            return null;
        }
        return new Band(Math.min(a[0], b[0]), Math.max(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[1], b[1]));
    }

    /** {row, col} of {@code "B4"}, or null. */
    private static int[] cellRef(String ref) {
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            i++;
        }
        if (i == 0 || i == ref.length()) {
            return null;
        }
        int col = columnNumber(ref.substring(0, i));
        try {
            int row = Integer.parseInt(ref.substring(i));
            return col > 0 && row > 0 ? new int[] {row, col} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code "B"} is 2, {@code "AA"} is 27; 0 for anything that is not column letters. */
    static int columnNumber(String letters) {
        if (letters == null || letters.isBlank()) {
            return 0;
        }
        int col = 0;
        for (char c : letters.trim().toUpperCase(Locale.ROOT).toCharArray()) {
            if (c < 'A' || c > 'Z') {
                return 0;
            }
            col = col * 26 + (c - 'A' + 1);
        }
        return col;
    }

    static String columnLetters(int col) {
        StringBuilder sb = new StringBuilder();
        for (int n = col; n > 0; n = (n - 1) / 26) {
            sb.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return sb.toString();
    }
}
