package com.resurgent.tev.parser.classify;

/**
 * Application classification of a Packet numeric cell for Layer B.
 * Distinct from spreadsheet {@code value_type=number}: not every number is money.
 */
public enum NumericKind {
    /** Currency / cost / monetary total. */
    MONEY,
    /** Counts, units, area, capacity, durations used as drivers. */
    QUANTITY,
    /** Price or intensity per unit (₹/sqft, rate). */
    RATE,
    /** Percentages (GST, interest, uplift). */
    PERCENT,
    /** Numeric without clear money/qty/rate cues. */
    UNKNOWN;

    public boolean isMoney() {
        return this == MONEY;
    }

    /** Cost-line roles that must not bind non-money numerics. */
    public boolean allowsCostRole() {
        return this == MONEY;
    }
}
