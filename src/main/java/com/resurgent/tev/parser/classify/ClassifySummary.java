package com.resurgent.tev.parser.classify;

/** Human-facing result of a Layer A classify run. */
public record ClassifySummary(
        long parseRunId,
        int dispositionCount,
        int skippedCount,
        int eligibleCount) {
}
