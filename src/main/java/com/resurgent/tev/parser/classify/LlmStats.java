package com.resurgent.tev.parser.classify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one pipeline run should leave behind for model comparisons: LLM calls per
 * (stage, model), the settings the run used, what each stage processed, and how long it took.
 * Collected in memory while the stages run, then written to the workspace database by the
 * command. One process runs one command, so a single {@link #GLOBAL} instance is enough, like
 * {@link LlmActivity#GLOBAL}.
 *
 * <p>Stages run one after another, so "the current stage" is a single volatile value. A call
 * made while no stage is entered is filed under {@code other}.
 */
public final class LlmStats {

    public static final LlmStats GLOBAL = new LlmStats();

    /** Totals for one model within one stage. */
    public record UsageRow(
            String stage, String modelId, long calls, long failedCalls, long failovers,
            long promptTokens, long completionTokens, Double costUsd, long costMissing,
            long latencyMsTotal) {}

    /** A numeric or text fact about the run or one of its stages. */
    public record Stat(String stage, String name, Double num, String text) {}

    public record Timing(String stage, Instant startedAt, Instant finishedAt, long durationMillis, Integer items) {}

    private static final class Acc {
        long calls;
        long failedCalls;
        long failovers;
        long promptTokens;
        long completionTokens;
        double costUsd;
        long costMissing;
        long latencyMs;
    }

    private volatile String stage = "other";
    private final Map<String, Acc> usage = new LinkedHashMap<>();
    private final Map<String, Stat> stats = new LinkedHashMap<>();
    private final List<Timing> timings = new ArrayList<>();

    /** Calls and stats made from now on belong to {@code stage} (region-layout, layer-a, layer-b). */
    public void enterStage(String stage) {
        this.stage = stage;
    }

    public String currentStage() {
        return stage;
    }

    /** A request that came back with a usable HTTP response and token usage. */
    public synchronized void recordCall(
            String modelId, long promptTokens, long completionTokens, Double costUsd, long latencyMs) {
        Acc acc = acc(modelId);
        acc.calls++;
        acc.promptTokens += promptTokens;
        acc.completionTokens += completionTokens;
        acc.latencyMs += latencyMs;
        if (costUsd == null) {
            acc.costMissing++;
        } else {
            acc.costUsd += costUsd;
        }
    }

    /** A request that failed at the HTTP level (error status, timeout, no answer). */
    public synchronized void recordFailedCall(String modelId, long latencyMs) {
        Acc acc = acc(modelId);
        acc.failedCalls++;
        acc.latencyMs += latencyMs;
    }

    /** A request was given up on this model (HTTP failure or unusable answer) and sent to the next. */
    public synchronized void recordFailover(String modelId) {
        acc(modelId).failovers++;
    }

    private Acc acc(String modelId) {
        return usage.computeIfAbsent(stage + "\u0000" + modelId, k -> new Acc());
    }

    /** Set a number for the current stage. */
    public void put(String name, double value) {
        put(stage, name, value);
    }

    public synchronized void put(String stage, String name, double value) {
        stats.put(stage + "\u0000" + name, new Stat(stage, name, value, null));
    }

    /** A number already recorded for {@code stage}, or null. */
    public synchronized Double stat(String stage, String name) {
        Stat stat = stats.get(stage + "\u0000" + name);
        return stat == null ? null : stat.num();
    }

    /** Add to a number of an explicit stage. */
    public synchronized void add(String stage, String name, double delta) {
        String key = stage + "\u0000" + name;
        Stat old = stats.get(key);
        double base = old != null && old.num() != null ? old.num() : 0;
        stats.put(key, new Stat(stage, name, base + delta, null));
    }

    public synchronized void putText(String stage, String name, String value) {
        if (value != null) {
            stats.put(stage + "\u0000" + name, new Stat(stage, name, null, value));
        }
    }

    /** Add to a number for the current stage; for counts a stage reaches in several passes. */
    public synchronized void add(String name, double delta) {
        String key = stage + "\u0000" + name;
        Stat old = stats.get(key);
        double base = old != null && old.num() != null ? old.num() : 0;
        stats.put(key, new Stat(stage, name, base + delta, null));
    }

    public synchronized void timing(String stage, Instant startedAt, Instant finishedAt, Integer items) {
        timings.add(new Timing(stage, startedAt, finishedAt,
                Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli()), items));
    }

    public synchronized List<UsageRow> usageRows() {
        List<UsageRow> rows = new ArrayList<>();
        for (Map.Entry<String, Acc> e : usage.entrySet()) {
            String[] key = e.getKey().split("\u0000", 2);
            Acc a = e.getValue();
            // Cost is null only when no call reported it; a mix keeps the partial sum and flags it.
            boolean anyCost = a.calls > a.costMissing;
            rows.add(new UsageRow(key[0], key[1], a.calls, a.failedCalls, a.failovers, a.promptTokens,
                    a.completionTokens, anyCost || a.calls == 0 ? a.costUsd : null, a.costMissing, a.latencyMs));
        }
        return rows;
    }

    public synchronized List<Stat> stats() {
        return new ArrayList<>(stats.values());
    }

    public synchronized List<Timing> timings() {
        return new ArrayList<>(timings);
    }

    /** Forget everything; used by tests and before a command starts. */
    public synchronized void reset() {
        stage = "other";
        usage.clear();
        stats.clear();
        timings.clear();
    }
}
