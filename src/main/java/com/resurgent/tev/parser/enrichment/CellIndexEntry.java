package com.resurgent.tev.parser.enrichment;

/** One filled cell in the enrichment cell index sent to the external model. */
public record CellIndexEntry(
        String coord,
        int row,
        int col,
        String kind,
        String display,
        String formula,
        String mergedRange) {}
