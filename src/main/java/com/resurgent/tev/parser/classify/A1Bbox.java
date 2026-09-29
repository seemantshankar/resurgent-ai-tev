package com.resurgent.tev.parser.classify;

/** A1-style bbox parsing for LLM region proposals (e.g. {@code A49:I62}). */
final class A1Bbox {

    record Bounds(int minRow, int minCol, int maxRow, int maxCol) {}

    private A1Bbox() {}

    static Bounds parse(String bbox) {
        if (bbox == null || bbox.isBlank()) {
            throw new IllegalArgumentException("blank bbox");
        }
        String normalized = bbox.trim().toUpperCase();
        String[] parts = normalized.split(":");
        if (parts.length == 1) {
            // Single cell → degenerate range P27:P27
            parts = new String[] {parts[0], parts[0]};
        }
        if (parts.length != 2) {
            throw new IllegalArgumentException("bbox must be Start:End or a single cell, got " + bbox);
        }
        int[] a = parseCoord(parts[0]);
        int[] b = parseCoord(parts[1]);
        return new Bounds(
                Math.min(a[0], b[0]),
                Math.min(a[1], b[1]),
                Math.max(a[0], b[0]),
                Math.max(a[1], b[1]));
    }

    /** @return {row, col} 1-based */
    private static int[] parseCoord(String coord) {
        int i = 0;
        while (i < coord.length() && Character.isLetter(coord.charAt(i))) {
            i++;
        }
        if (i == 0 || i == coord.length()) {
            throw new IllegalArgumentException("bad coord " + coord);
        }
        int col = 0;
        for (int j = 0; j < i; j++) {
            col = col * 26 + (coord.charAt(j) - 'A' + 1);
        }
        int row = Integer.parseInt(coord.substring(i));
        if (row < 1 || col < 1) {
            throw new IllegalArgumentException("bad coord " + coord);
        }
        return new int[] {row, col};
    }
}
