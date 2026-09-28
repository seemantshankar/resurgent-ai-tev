package com.resurgent.tev.parser.classify;

/** Cells that received a Layer B path, and cells that correctly received none. */
public record BindSummary(long parseRunId, int boundCells, int skippedCells) {
}
