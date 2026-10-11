package com.resurgent.tev.doc.layout;

import java.util.List;

/**
 * A structural region of a page: paragraph, heading, or table.
 * Tables carry a grid of cell texts aligned to {@link #lines()} row order.
 */
public record LayoutBlock(
        String blockKind,
        BBox bbox,
        String textRaw,
        String textNorm,
        List<LayoutLine> lines,
        List<List<String>> tableRows,
        List<BBox> tableRowBoxes) {

    public static LayoutBlock paragraph(BBox bbox, String textRaw, String textNorm,
            List<LayoutLine> lines) {
        return new LayoutBlock("paragraph", bbox, textRaw, textNorm, lines, null, null);
    }

    public static LayoutBlock heading(BBox bbox, String textRaw, String textNorm,
            List<LayoutLine> lines) {
        return new LayoutBlock("heading", bbox, textRaw, textNorm, lines, null, null);
    }

    public static LayoutBlock table(BBox bbox, String textRaw, String textNorm,
            List<LayoutLine> lines, List<List<String>> tableRows, List<BBox> rowBoxes) {
        return new LayoutBlock("table", bbox, textRaw, textNorm, lines, tableRows, rowBoxes);
    }

    public boolean isTable() {
        return "table".equals(blockKind) && tableRows != null;
    }
}
