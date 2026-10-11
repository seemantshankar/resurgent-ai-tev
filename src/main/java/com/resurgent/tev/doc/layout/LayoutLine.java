package com.resurgent.tev.doc.layout;

import java.util.List;

/** One extracted line of text with per-glyph (word) boxes. */
public record LayoutLine(String text, BBox bbox, List<LayoutSpan> spans) {

    public double yCenter() {
        return bbox.centerY();
    }

    public double height() {
        return bbox.height();
    }
}
