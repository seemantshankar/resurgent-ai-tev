package com.resurgent.tev.doc.extract;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Duration parsing (Architecture §7.4): {@code 25 years}, {@code 25 Yr}, {@code 25} + unit label.
 * Stores {@code value_number} plus {@code unit} in days/years as stated.
 */
public final class DurationNormalizer {

    private static final Pattern DURATION = Pattern.compile(
            "(?i)^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*"
                    + "(years?|yrs?|months?|mos?|days?|weeks?)?\\s*$");

    private DurationNormalizer() {}

    /**
     * Parses a duration phrase.
     *
     * @param raw source text (required)
     * @return normalized duration slots
     */
    public static Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.empty();
        }
        Matcher m = DURATION.matcher(raw.trim());
        if (!m.matches()) {
            return Result.empty();
        }
        double value = Double.parseDouble(m.group(1));
        String unitToken = m.group(2);
        String unit;
        if (unitToken == null || unitToken.isBlank()) {
            unit = null;
        } else {
            unit = normalizeUnit(unitToken);
        }
        return new Result(value, unit, raw.trim(), unit != null);
    }

    private static String normalizeUnit(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        if (t.startsWith("year") || t.startsWith("yr")) {
            return "years";
        }
        if (t.startsWith("month") || t.startsWith("mo")) {
            return "months";
        }
        if (t.startsWith("week")) {
            return "weeks";
        }
        if (t.startsWith("day")) {
            return "days";
        }
        return t;
    }

    /**
     * Parsed duration.
     *
     * @param valueNumber numeric magnitude
     * @param unit years/months/weeks/days as stated, or null when unstated
     * @param valueText display text
     * @param ok true when a unit was stated (unstated unit is ambiguous)
     */
    public record Result(Double valueNumber, String unit, String valueText, boolean ok) {

        public static Result empty() {
            return new Result(null, null, null, false);
        }
    }
}
