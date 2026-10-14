package com.resurgent.tev.doc.extract;

import java.util.Objects;

/**
 * One cited quote for a fact candidate: page + optional bbox + exact verbatim text.
 * Bbox stays null when the extractor returns no geometry; the quote is still citable.
 */
public record Citation(
        int pageIndex,
        Double bboxX0,
        Double bboxY0,
        Double bboxX1,
        Double bboxY1,
        String verbatim) {

    public Citation {
        Objects.requireNonNull(verbatim, "verbatim");
        if (pageIndex < 1) {
            throw new IllegalArgumentException("pageIndex must be 1-based");
        }
    }

    /** Citation with page and quote only (no geometry). */
    public static Citation of(int pageIndex, String verbatim) {
        return new Citation(pageIndex, null, null, null, null, verbatim);
    }

    /** Citation including bbox coordinates in page space. */
    public static Citation of(int pageIndex, double x0, double y0, double x1, double y1,
            String verbatim) {
        return new Citation(pageIndex, x0, y0, x1, y1, verbatim);
    }

    /** True when any bbox coordinate is present. */
    public boolean hasBbox() {
        return bboxX0 != null;
    }
}
