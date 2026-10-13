package com.resurgent.tev.doc.extract;

import java.util.Objects;

/**
 * Call counts / tokens / cost reported by an extractor. Mapped to {@code llm_usage}.
 */
public record ExtractStats(
        int calls,
        int failedCalls,
        int promptTokens,
        int completionTokens,
        Double costUsd,
        long latencyMsTotal) {

    public ExtractStats {
        if (calls < 0 || failedCalls < 0 || promptTokens < 0
                || completionTokens < 0 || latencyMsTotal < 0) {
            throw new IllegalArgumentException("stats must be non-negative");
        }
    }

    /** Zeroed stats for offline extractors. */
    public static ExtractStats empty() {
        return new ExtractStats(0, 0, 0, 0, null, 0L);
    }

    /** Stats for one local call with no token accounting. */
    public static ExtractStats local(long latencyMs) {
        return new ExtractStats(1, 0, 0, 0, null, latencyMs);
    }

    /** Merges two stat bags (used when a document is extracted in page batches). */
    public ExtractStats plus(ExtractStats other) {
        Objects.requireNonNull(other, "other");
        Double cost = null;
        if (costUsd != null && other.costUsd != null) {
            cost = costUsd + other.costUsd;
        } else {
            cost = costUsd != null ? costUsd : other.costUsd;
        }
        return new ExtractStats(
                calls + other.calls,
                failedCalls + other.failedCalls,
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                cost,
                latencyMsTotal + other.latencyMsTotal);
    }
}
