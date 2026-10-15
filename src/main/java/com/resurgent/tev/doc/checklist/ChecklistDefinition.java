package com.resurgent.tev.doc.checklist;

import java.util.List;

/**
 * A versioned checklist definition, stored as data alongside packs (never in
 * code — the pack-store precedent). Scope is a country spine or an industry
 * checklist keyed by industry <em>and</em> country (spec #133).
 *
 * @param code definition code ({@code in.spine}, {@code in.solar_power})
 * @param version semver of this definition
 * @param scope {@code spine} | {@code industry}
 * @param country ISO-like country code the definition applies to
 * @param industries industries the definition applies to (empty for a spine)
 * @param items the definition's items, every one with its basis
 * @param note the definition's label when it carries one ({@code new industry:
 *     first mandate} for a starter draft), or null
 */
public record ChecklistDefinition(
        String code,
        String version,
        String scope,
        String country,
        List<String> industries,
        List<ChecklistItemDef> items,
        String note) {

    public ChecklistDefinition {
        industries = industries == null ? List.of() : List.copyOf(industries);
        items = items == null ? List.of() : List.copyOf(items);
        note = note == null || note.isBlank() ? null : note;
    }

    /** A definition without a note — the common case (spine and learned drafts). */
    public ChecklistDefinition(String code, String version, String scope, String country,
            List<String> industries, List<ChecklistItemDef> items) {
        this(code, version, scope, country, industries, items, null);
    }

    /** True for a country spine (applies to every mandate in the country). */
    public boolean isSpine() {
        return "spine".equals(scope);
    }

    /** True when this definition applies to the given industry. */
    public boolean coversIndustry(String industry) {
        return industry != null && industries.contains(industry);
    }
}
