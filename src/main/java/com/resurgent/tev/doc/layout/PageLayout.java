package com.resurgent.tev.doc.layout;

import java.util.ArrayList;
import java.util.List;

/** All layout blocks extracted from one page, in reading order. */
public record PageLayout(int pageIndex, double widthPt, double heightPt,
        Integer widthPx, Integer heightPx, List<LayoutBlock> blocks) {

    /**
     * Copies the block list into an unmodifiable list.
     *
     * @throws NullPointerException if the list or any block is null
     */
    public PageLayout {
        blocks = List.copyOf(blocks);
    }

    public static PageLayout empty(int pageIndex, double widthPt, double heightPt,
            Integer widthPx, Integer heightPx) {
        return new PageLayout(pageIndex, widthPt, heightPt, widthPx, heightPx, new ArrayList<>());
    }
}
