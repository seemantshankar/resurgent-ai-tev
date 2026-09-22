package com.resurgent.tev.parser.classify;

import java.time.Duration;
import java.util.Objects;

/**
 * Hang-prevention budgets for one classify run (#122). The live ASSETS+CAPITAL
 * COST wall-clock gate is a quality assertion, not this abort budget.
 */
public record ClassifyLimits(
        int parallelism,
        Duration attemptDeadline,
        Duration classifyDeadline,
        Duration retryAttemptDeadline) {

    /**
     * Concurrent LLM calls. The live workbook runs showed no throttling at 4 or at 8
     * (zero 429s, zero retries across ~900 calls), so a parallel slot is throughput
     * here rather than contention. The OpenRouter 429 log line is the instrument that
     * would show a real allowance being reached; override with {@code --parallelism}.
     */
    public static final int DEFAULT_PARALLELISM = 8;
    public static final Duration DEFAULT_ATTEMPT_DEADLINE = Duration.ofMinutes(3);
    public static final Duration DEFAULT_CLASSIFY_DEADLINE = Duration.ofMinutes(15);

    /**
     * Second-chance budget for one Layer A straggler. The observed tail is per-request
     * provider scheduling — byte-identical prompts return in 2s or 112s — so a fresh
     * request often lands fast, and abandoning at this budget costs a worker slot far
     * less than holding it for the full attempt deadline.
     */
    public static final Duration DEFAULT_RETRY_ATTEMPT_DEADLINE = Duration.ofSeconds(60);

    public ClassifyLimits {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
        Objects.requireNonNull(attemptDeadline, "attemptDeadline");
        Objects.requireNonNull(classifyDeadline, "classifyDeadline");
        Objects.requireNonNull(retryAttemptDeadline, "retryAttemptDeadline");
        if (attemptDeadline.isNegative() || attemptDeadline.isZero()) {
            throw new IllegalArgumentException("attemptDeadline must be positive");
        }
        if (classifyDeadline.isNegative() || classifyDeadline.isZero()) {
            throw new IllegalArgumentException("classifyDeadline must be positive");
        }
        if (retryAttemptDeadline.isNegative() || retryAttemptDeadline.isZero()) {
            throw new IllegalArgumentException("retryAttemptDeadline must be positive");
        }
    }

    public ClassifyLimits(
            int parallelism, Duration attemptDeadline, Duration classifyDeadline) {
        this(parallelism, attemptDeadline, classifyDeadline, DEFAULT_RETRY_ATTEMPT_DEADLINE);
    }

    public static ClassifyLimits defaults() {
        return new ClassifyLimits(
                DEFAULT_PARALLELISM,
                DEFAULT_ATTEMPT_DEADLINE,
                DEFAULT_CLASSIFY_DEADLINE,
                DEFAULT_RETRY_ATTEMPT_DEADLINE);
    }
}
