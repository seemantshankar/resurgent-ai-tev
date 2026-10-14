package com.resurgent.tev.doc.llm;

/**
 * Narrow port for the document-side LLM calls (classify tie-break, pack mining).
 * Domain code never sees a vendor wire format; adapters own it. Tests inject a fake.
 *
 * <p>This is deliberately separate from the Excel parser's {@code ClassifierLlm}: the
 * document pipeline only needs one JSON chat completion per call, and it must stay
 * testable without a network.
 */
public interface DocLlmClient {

    /** Stable adapter id, e.g. {@code openrouter}. */
    String id();

    /**
     * Runs one chat completion that must return JSON.
     *
     * @param system system instruction (required)
     * @param user   user payload (required)
     * @param maxTokens completion budget
     * @return the model completion and its usage, never null
     * @throws DocLlmException on timeout, HTTP failure, or an unusable reply
     */
    Completion complete(String system, String user, int maxTokens) throws DocLlmException;

    /** One completion's content plus the tokens/cost/latency to persist. */
    record Completion(
            String content,
            String modelId,
            Integer promptTokens,
            Integer completionTokens,
            Double costUsd,
            long latencyMs) {

        public Completion {
            content = content == null ? "" : content;
        }

        /** Usage totals for {@code llm_usage}; one call, zero failures. */
        public Usage usage() {
            return new Usage(modelId,
                    1, 0,
                    promptTokens == null ? 0 : promptTokens,
                    completionTokens == null ? 0 : completionTokens,
                    costUsd, latencyMs);
        }
    }

    /** Accumulated usage for one stage, ready for {@code WorkspaceRepository.recordLlmUsage}. */
    record Usage(
            String modelId,
            int calls,
            int failedCalls,
            long promptTokens,
            long completionTokens,
            Double costUsd,
            long latencyMsTotal) {

        /** Adds two totals for the same model. */
        public Usage plus(Usage other) {
            if (other == null) {
                return this;
            }
            Double cost = costUsd == null && other.costUsd == null
                    ? null
                    : (costUsd == null ? 0 : costUsd) + (other.costUsd == null ? 0 : other.costUsd);
            return new Usage(modelId, calls + other.calls, failedCalls + other.failedCalls,
                    promptTokens + other.promptTokens, completionTokens + other.completionTokens,
                    cost, latencyMsTotal + other.latencyMsTotal);
        }

        /** A failed call with no reply to account for. */
        public static Usage failed(String modelId, long latencyMs) {
            return new Usage(modelId, 1, 1, 0, 0, null, latencyMs);
        }
    }
}
