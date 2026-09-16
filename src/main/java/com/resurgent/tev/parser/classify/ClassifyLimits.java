package com.resurgent.tev.parser.classify;

import java.time.Duration;
import java.util.Objects;

/**
 * Hang-prevention budgets for one classify run (#122). The live ASSETS+CAPITAL
 * COST wall-clock gate is a quality assertion, not this abort budget.
 */
public record ClassifyLimits(int parallelism, Duration attemptDeadline, Duration classifyDeadline) {

    public static final int DEFAULT_PARALLELISM = 8;
    public static final Duration DEFAULT_ATTEMPT_DEADLINE = Duration.ofMinutes(3);
    public static final Duration DEFAULT_CLASSIFY_DEADLINE = Duration.ofMinutes(15);

    public ClassifyLimits {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
        Objects.requireNonNull(attemptDeadline, "attemptDeadline");
        Objects.requireNonNull(classifyDeadline, "classifyDeadline");
        if (attemptDeadline.isNegative() || attemptDeadline.isZero()) {
            throw new IllegalArgumentException("attemptDeadline must be positive");
        }
        if (classifyDeadline.isNegative() || classifyDeadline.isZero()) {
            throw new IllegalArgumentException("classifyDeadline must be positive");
        }
    }

    public static ClassifyLimits defaults() {
        return new ClassifyLimits(
                DEFAULT_PARALLELISM, DEFAULT_ATTEMPT_DEADLINE, DEFAULT_CLASSIFY_DEADLINE);
    }
}
