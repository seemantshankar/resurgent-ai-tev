package com.resurgent.tev.parser.ingest;

/**
 * Deterministic accounting facts behind Sprint 3a's region QA gates.
 *
 * <p>A classified region is a non-unknown classification at or above the
 * confidence floor. Every remaining region must have its own review-queue
 * entry; {@code regionsUnaccounted} is the only classification shortfall.
 */
public record RegionQaStats(int regionsTotal, int cellsWithoutRegion,
        int regionsClassified, int regionsQueuedForReview, int regionsUnaccounted) {
}
