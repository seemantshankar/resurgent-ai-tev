package com.resurgent.tev.parser.discover;

import java.util.List;

/**
 * Human-facing result of a discover run: worksheet and Candidate counts only (no Packet dump).
 */
public record DiscoverSummary(
        long parseRunId,
        int worksheetCount,
        int candidateCount,
        int isolatedHiddenWorksheetCount,
        boolean coverageCheckPassed,
        List<String> unavailableIngestSignals) {

    /** Signals absent from the ingest contract (ADR 0013) — discover must not invent them. */
    public static final List<String> UNAVAILABLE_INGEST_SIGNALS = List.of(
            "column_width",
            "font",
            "comments",
            "drawings");
}
