package com.resurgent.tev.parser.classify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The cost of one run as printed at its end: a total over every model the run called (the chat models
 * and the decision model), then one line per stage and model.
 */
public final class UsageReport {

    private UsageReport() {}

    /** {@code LLM_COST_TOTAL} first, then {@code LLM_COST} per stage and model; empty when nothing was called. */
    public static List<String> lines(List<LlmStats.UsageRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        long calls = 0;
        long failed = 0;
        long prompt = 0;
        long completion = 0;
        long missing = 0;
        double cost = 0;
        for (LlmStats.UsageRow r : rows) {
            calls += r.calls();
            failed += r.failedCalls();
            prompt += r.promptTokens();
            completion += r.completionTokens();
            missing += r.costMissing();
            cost += r.costUsd() == null ? 0 : r.costUsd();
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.US,
                "LLM_COST_TOTAL calls=%d failed=%d prompt_tokens=%d completion_tokens=%d cost_usd=%.6f cost_missing=%d%s",
                calls, failed, prompt, completion, cost, missing,
                missing > 0 ? " (partial: some calls did not report a cost)" : ""));
        for (LlmStats.UsageRow r : rows) {
            lines.add(String.format(Locale.US,
                    "LLM_COST stage=%s model=%s calls=%d failed=%d prompt_tokens=%d completion_tokens=%d cost_usd=%s",
                    r.stage(), r.modelId(), r.calls(), r.failedCalls(), r.promptTokens(), r.completionTokens(),
                    r.costUsd() == null ? "unknown" : String.format(Locale.US, "%.6f", r.costUsd())));
        }
        return lines;
    }
}
