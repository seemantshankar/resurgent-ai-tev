package com.resurgent.tev.parser.discover;

/**
 * Human-facing result of a discover run: worksheet and Candidate counts only (no Packet dump).
 */
public record DiscoverSummary(
        long parseRunId,
        int worksheetCount,
        int candidateCount,
        int isolatedHiddenWorksheetCount,
        boolean coverageCheckPassed) {
}
