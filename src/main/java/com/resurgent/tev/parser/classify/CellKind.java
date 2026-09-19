package com.resurgent.tev.parser.classify;

import java.util.Locale;

/**
 * The dimension a numeric cell carries, used by propagation. Wider than
 * {@link NumericKind}: propagation needs {@code RATIO} (money over money) and
 * {@code COUNT} as distinct results of dimensional arithmetic.
 */
public enum CellKind {
    MONEY,
    QUANTITY,
    RATE,
    PERCENT,
    COUNT,
    RATIO;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CellKind fromWire(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /** Only money may take a cost role; a non-money group can never carry one. */
    public boolean allowsCostRole() {
        return this == MONEY;
    }

    public static CellKind from(NumericKind kind) {
        return switch (kind) {
            case MONEY -> MONEY;
            case QUANTITY -> QUANTITY;
            case RATE -> RATE;
            case PERCENT -> PERCENT;
            case UNKNOWN -> null;
        };
    }
}
