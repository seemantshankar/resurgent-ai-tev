package com.resurgent.tev.doc.extract;

import java.util.Objects;

/**
 * Call counts / tokens / spend reported by an extractor. Mapped to {@code llm_usage}.
 * The spend slot carries the extractor's <em>native</em> unit: LlamaExtract bills
 * LlamaCloud <b>credits</b> (converted to USD only by the cost guard, never for
 * storage); OpenRouter spend is USD and lives on {@code DocLlmClient.Usage}.
 */
public record ExtractStats(
        int calls,
        int failedCalls,
        int promptTokens,
        int completionTokens,
        Double credits,
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
        if (credits != null && other.credits != null) {
            cost = credits + other.credits;
        } else {
            cost = credits != null ? credits : other.credits;
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
