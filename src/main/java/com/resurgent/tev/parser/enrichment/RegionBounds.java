package com.resurgent.tev.parser.enrichment;

import java.util.regex.Pattern;

/** Canonical Excel A1-style region bounds (e.g. {@code F2:F2} for a single cell). */
final class RegionBounds {

    private static final Pattern CELL_ADDRESS =
            Pattern.compile("^[A-Z]{1,3}[1-9][0-9]{0,6}$");

    private RegionBounds() {}

    static String normalize(String bounds) {
        if (bounds == null || bounds.isBlank()) {
            return bounds;
        }
        String trimmed = bounds.strip();
        if (!trimmed.contains(":") && CELL_ADDRESS.matcher(trimmed).matches()) {
            return trimmed + ":" + trimmed;
        }
        return trimmed;
    }
}
