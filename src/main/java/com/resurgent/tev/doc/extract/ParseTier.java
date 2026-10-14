package com.resurgent.tev.doc.extract;

import java.util.Locale;

/**
 * LlamaParse quality/cost tiers (official tier guide).
 *
 * <ul>
 *   <li>{@link #FAST} — plain text, single column, no tables; raw or layout-preserving text</li>
 *   <li>{@link #COST_EFFECTIVE} — mostly text with simple tables; clean markdown, lower cost</li>
 *   <li>{@link #AGENTIC} — scans, multi-column, real tables, embedded charts</li>
 *   <li>{@link #AGENTIC_PLUS} — dense financials, complex tables/charts, mission-critical accuracy</li>
 * </ul>
 */
public enum ParseTier {
    FAST("fast"),
    COST_EFFECTIVE("cost_effective"),
    AGENTIC("agentic"),
    AGENTIC_PLUS("agentic_plus");

    private final String wireName;

    ParseTier(String wireName) {
        this.wireName = wireName;
    }

    /** Wire name accepted by the Parse API ({@code fast}, {@code cost_effective}, …). */
    public String wireName() {
        return wireName;
    }

    /**
     * Documented LlamaParse credits per page for this tier — the rates behind the
     * adapter's pre-submit spend estimate ({@code fast} 1, {@code cost_effective} 3,
     * {@code agentic} 10, {@code agentic_plus} 45; see the official tier guide, also
     * referenced on {@code LlamaParseClient}).
     *
     * @return credits billed per processed page on this tier
     */
    public int creditsPerPage() {
        return switch (this) {
            case FAST -> 1;
            case COST_EFFECTIVE -> 3;
            case AGENTIC -> 10;
            case AGENTIC_PLUS -> 45;
        };
    }

    /**
     * Parses a tier name (case-insensitive; hyphens allowed).
     *
     * @throws IllegalArgumentException when the name is not a known tier
     */
    public static ParseTier fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("parse tier is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "fast" -> FAST;
            case "cost_effective", "costeffective" -> COST_EFFECTIVE;
            case "agentic" -> AGENTIC;
            case "agentic_plus", "agenticplus" -> AGENTIC_PLUS;
            default -> throw new IllegalArgumentException("unknown parse tier: " + value);
        };
    }
}
