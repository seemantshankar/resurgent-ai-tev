package com.resurgent.tev.parser.classify;

import java.util.Locale;

/**
 * The unit a numeric cell's figure is expressed in. {@code multiplier()} is how many
 * base units one displayed unit stands for, so a figure in lakhs has multiplier
 * 100,000. Rupees and lakhs sit in one block in a real FM, so propagation carries
 * scale and two members of one aggregation with conflicting scale are refused
 * rather than silently added.
 */
public enum CellScale {
    UNIT(1d),
    THOUSAND(1_000d),
    LAKH(100_000d),
    MILLION(1_000_000d),
    CRORE(10_000_000d),
    BILLION(1_000_000_000d);

    private final double multiplier;

    CellScale(double multiplier) {
        this.multiplier = multiplier;
    }

    public double multiplier() {
        return multiplier;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CellScale fromWire(String value) {
        if (value == null || value.isBlank()) {
            return UNIT;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * The scale reached by dividing this scale's figures by {@code divisor}: rupees
     * divided by 100,000 are lakhs, so the displayed unit grows by the divisor.
     * Null when the result is not a scale this enum names.
     */
    public static CellScale dividedBy(CellScale scale, double divisor) {
        if (divisor == 0d) {
            return null;
        }
        double target = scale.multiplier() * divisor;
        for (CellScale candidate : values()) {
            if (Math.abs(candidate.multiplier() - target) < 1e-6) {
                return candidate;
            }
        }
        return null;
    }

    /** The scale reached by multiplying this scale's figures by {@code factor}. */
    public static CellScale multipliedBy(CellScale scale, double factor) {
        if (factor == 0d) {
            return null;
        }
        return dividedBy(scale, 1d / factor);
    }
}
