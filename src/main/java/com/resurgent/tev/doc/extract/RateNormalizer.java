package com.resurgent.tev.doc.extract;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Percent / rate parsing. Never invents {@code unit}: unstated rate unit leaves
 * {@code unit=null} and evidence {@code resolution='ambiguous'} (Architecture §7.4).
 */
public final class RateNormalizer {

    private static final Pattern PERCENT = Pattern.compile(
            "^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%\\s*$");
    private static final Pattern RATE_WITH_UNIT = Pattern.compile(
            "(?i)^\\s*(?:₹|rs\\.?|inr)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*"
                    + "/\\s*(kwh|kw|unit|month|year|annum|day|sqm|sqft|mt|tonne|ton)\\s*$");
    private static final Pattern PER_ANNUM = Pattern.compile(
            "(?i)^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%\\s*(?:p\\.?a\\.?|per\\s+annum)?\\s*$");

    private RateNormalizer() {}

    /**
     * Parses a percent or rate string.
     *
     * @param raw source text (required)
     * @return normalized rate slots with unit only when stated
     */
    public static Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.empty();
        }
        String text = raw.trim();
        Matcher pct = PERCENT.matcher(text);
        if (pct.matches()) {
            return new Result(Double.parseDouble(pct.group(1)), "percent", text, true);
        }
        Matcher pa = PER_ANNUM.matcher(text);
        if (pa.matches()) {
            String unit = text.toLowerCase(Locale.ROOT).contains("a") ? "percent_pa" : "percent";
            return new Result(Double.parseDouble(pa.group(1)), unit, text, true);
        }
        Matcher rate = RATE_WITH_UNIT.matcher(text);
        if (rate.matches()) {
            String unit = rate.group(2).toLowerCase(Locale.ROOT);
            return new Result(Double.parseDouble(rate.group(1)), unit, text, true);
        }
        QuantityNormalizer.Result technical = QuantityNormalizer.parse(text);
        if (technical.valueNumber() != null && isTechnicalUnit(technical.unit())) {
            return new Result(technical.valueNumber(), technical.unit(), text, true);
        }
        // bare number with no unit → value yes, unit never invented
        try {
            double v = Double.parseDouble(text.replace(",", ""));
            return new Result(v, null, text, false);
        } catch (NumberFormatException e) {
            return Result.empty();
        }
    }

    private static boolean isTechnicalUnit(String unit) {
        return switch (unit == null ? "" : unit) {
            case "kwp", "mwp", "kwh_m2", "kwh_kwp", "mwh_yr", "tpd", "klpd", "mva", "kv" -> true;
            default -> false;
        };
    }

    /**
     * Converts a BigDecimal number to Double safely.
     */
    public static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /**
     * Parsed rate/percent.
     *
     * @param valueNumber numeric magnitude
     * @param unit unit only when the document states it; null otherwise
     * @param valueText display text
     * @param unitStated true when a unit token was present
     */
    public record Result(Double valueNumber, String unit, String valueText, boolean unitStated) {

        public static Result empty() {
            return new Result(null, null, null, false);
        }
    }
}
