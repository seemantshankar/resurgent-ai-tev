package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.CompletionResult;
import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.CompletionsClient;
import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.UsageTotals;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Tries a chain of models in order. Each request is independent: when one fails (HTTP error,
 * timeout, provider outage) only that request goes to the next model; requests that succeeded
 * are never re-sent. A model that fails several times in a row is skipped for a cool-off so
 * a dead provider does not cost a full timeout on every remaining request.
 */
final class FallbackCompletionsClient implements CompletionsClient {

    static final int SKIP_AFTER_CONSECUTIVE_FAILURES = 3;
    static final long COOL_OFF_NANOS = 5L * 60L * 1_000_000_000L;

    /** One model in the chain. */
    record Link(String model, CompletionsClient client) {}

    private final List<Link> chain;
    private final LongSupplier nanoClock;
    private final int[] consecutiveFailures;
    private final long[] skipUntil;

    FallbackCompletionsClient(List<Link> chain) {
        this(chain, System::nanoTime);
    }

    FallbackCompletionsClient(List<Link> chain, LongSupplier nanoClock) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("model chain must not be empty");
        }
        this.chain = List.copyOf(chain);
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.consecutiveFailures = new int[chain.size()];
        this.skipUntil = new long[chain.size()];
    }

    @Override
    public CompletionResult completeJson(String system, String user, int maxCompletionTokens) {
        return attempt(link -> link.client().completeJson(system, user, maxCompletionTokens));
    }

    @Override
    public CompletionResult completeLayerA(String system, String user, List<String> scheduleFamilies) {
        return attempt(link -> link.client().completeLayerA(system, user, scheduleFamilies));
    }

    /**
     * Run a whole operation (call, truncation retry, parsing) against each model in turn. A
     * bad or unparseable answer counts as a failure of that model, so it moves on exactly as
     * a transport error does.
     */
    @Override
    public <T> T perModel(Function<CompletionsClient, T> operation) {
        return attempt(link -> operation.apply(link.client()));
    }

    @Override
    public UsageTotals usageTotals() {
        long calls = 0;
        long prompt = 0;
        long completion = 0;
        double cost = 0;
        long missing = 0;
        for (Link link : chain) {
            UsageTotals u = link.client().usageTotals();
            calls += u.calls();
            prompt += u.promptTokens();
            completion += u.completionTokens();
            cost += u.costUsd();
            missing += u.costMissing();
        }
        return new UsageTotals(calls, prompt, completion, cost, missing);
    }

    private <T> T attempt(Function<Link, T> call) {
        // Models in cool-off are tried last, so a chain whose every model is cooling still gets a try.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            if (!isCoolingOff(i)) {
                order.add(i);
            }
        }
        for (int i = 0; i < chain.size(); i++) {
            if (isCoolingOff(i)) {
                order.add(i);
            }
        }
        List<String> failures = new ArrayList<>();
        RuntimeException last = null;
        for (int n = 0; n < order.size(); n++) {
            int i = order.get(n);
            Link link = chain.get(i);
            try {
                T result = call.apply(link);
                recordSuccess(i);
                return result;
            } catch (RuntimeException e) {
                last = e;
                recordFailure(i, link.model());
                failures.add(link.model() + ": " + e.getMessage());
                System.err.println("[llm-fallback] model " + link.model() + " failed: " + e.getMessage()
                        + (n + 1 < order.size() ? " - re-sending this request to " + chain.get(order.get(n + 1)).model() : ""));
                System.err.flush();
            }
        }
        throw new IllegalStateException("all models failed: " + String.join(" | ", failures), last);
    }

    private synchronized boolean isCoolingOff(int i) {
        return skipUntil[i] != 0 && nanoClock.getAsLong() - skipUntil[i] < 0;
    }

    private synchronized void recordSuccess(int i) {
        consecutiveFailures[i] = 0;
        skipUntil[i] = 0;
    }

    private synchronized void recordFailure(int i, String model) {
        if (++consecutiveFailures[i] >= SKIP_AFTER_CONSECUTIVE_FAILURES) {
            skipUntil[i] = nanoClock.getAsLong() + COOL_OFF_NANOS;
            consecutiveFailures[i] = 0;
            System.err.println("[llm-fallback] model " + model + " failed "
                    + SKIP_AFTER_CONSECUTIVE_FAILURES + " times in a row; skipping it for 5 minutes");
            System.err.flush();
        }
    }
}
