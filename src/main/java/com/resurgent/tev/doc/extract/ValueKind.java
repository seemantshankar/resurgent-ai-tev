package com.resurgent.tev.doc.extract;

/**
 * Closed set of fact value kinds (Architecture §6.5).
 */
public enum ValueKind {
    MONEY,
    QUANTITY,
    RATE,
    PERCENT,
    DATE,
    DURATION,
    TEXT,
    ENUM,
    BOOLEAN,
    LIST;

    /** Wire name as stored in {@code fact.value_kind} and pack YAML. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Parses a pack/schema wire name into this enum.
     *
     * @throws IllegalArgumentException when the name is not in the closed set
     */
    public static ValueKind fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value_kind is required");
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
