package com.resurgent.tev.parser.db;

/**
 * Minimal A1 range bounds for Packet formula-target expansion. Not a full Excel parser.
 */
final class A1Range {

    private final int minRow;
    private final int maxRow;
    private final int minCol;
    private final int maxCol;
    private final boolean wholeColumn;
    private final boolean wholeRow;

    private A1Range(
            int minRow, int maxRow, int minCol, int maxCol, boolean wholeColumn, boolean wholeRow) {
        this.minRow = minRow;
        this.maxRow = maxRow;
        this.minCol = minCol;
        this.maxCol = maxCol;
        this.wholeColumn = wholeColumn;
        this.wholeRow = wholeRow;
    }

    static A1Range parse(String raw) {
        String range = raw;
        int bang = range.lastIndexOf('!');
        if (bang >= 0) {
            range = range.substring(bang + 1);
        }
        range = range.replace("$", "");
        if (range.isEmpty()) {
            return null;
        }
        String[] parts = range.split(":");
        CellRef start = CellRef.parse(parts[0]);
        if (start == null) {
            return null;
        }
        CellRef end = parts.length > 1 ? CellRef.parse(parts[1]) : start;
        if (end == null) {
            return null;
        }
        boolean wholeCol = start.row == null && end.row == null;
        boolean wholeRow = start.col == null && end.col == null;
        int minRow = start.row != null ? start.row : 1;
        int maxRow = end.row != null ? end.row : (start.row != null ? start.row : Integer.MAX_VALUE);
        int minCol = start.col != null ? start.col : 1;
        int maxCol = end.col != null ? end.col : (start.col != null ? start.col : Integer.MAX_VALUE);
        if (minRow > maxRow) {
            int tmp = minRow;
            minRow = maxRow;
            maxRow = tmp;
        }
        if (minCol > maxCol) {
            int tmp = minCol;
            minCol = maxCol;
            maxCol = tmp;
        }
        return new A1Range(minRow, maxRow, minCol, maxCol, wholeCol, wholeRow);
    }

    boolean contains(int rowNum, int colNum) {
        if (wholeColumn) {
            return colNum >= minCol && colNum <= maxCol;
        }
        if (wholeRow) {
            return rowNum >= minRow && rowNum <= maxRow;
        }
        return rowNum >= minRow && rowNum <= maxRow && colNum >= minCol && colNum <= maxCol;
    }

    private record CellRef(Integer row, Integer col) {
        static CellRef parse(String token) {
            if (token == null || token.isEmpty()) {
                return null;
            }
            int i = 0;
            while (i < token.length() && Character.isLetter(token.charAt(i))) {
                i++;
            }
            String colPart = token.substring(0, i);
            String rowPart = token.substring(i);
            Integer col = colPart.isEmpty() ? null : colLettersToNumber(colPart);
            Integer row = rowPart.isEmpty() ? null : Integer.parseInt(rowPart);
            if (col == null && row == null) {
                return null;
            }
            return new CellRef(row, col);
        }

        private static int colLettersToNumber(String letters) {
            int n = 0;
            for (int i = 0; i < letters.length(); i++) {
                char ch = Character.toUpperCase(letters.charAt(i));
                if (ch < 'A' || ch > 'Z') {
                    return 0;
                }
                n = n * 26 + (ch - 'A' + 1);
            }
            return n;
        }
    }
}
