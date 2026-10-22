package com.resurgent.tev.doc.extract;

import com.resurgent.tev.parser.classify.KindTokens;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic quantity parsing: a figure plus, when the text writes one, a
 * short unit token canonicalized with the Excel side's own
 * {@link KindTokens#normalizeUnit} alias rules — so a document's "200 rooms"
 * keys exactly like a model cell's rooms column ("room").
 */
public final class QuantityNormalizer {

    private static final Pattern NUMBER_UNIT = Pattern.compile(
            "^\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*"
                    + "([A-Za-zµ][A-Za-z0-9µ/²³°-]{0,23})?\\s*$");
    private static final Pattern SCIENTIFIC_TAIL = Pattern.compile("[Ee][+-]?[0-9]+");

    private QuantityNormalizer() {}

    /**
     * Parses {@code "4.5 acres"} / {@code "1200 kWp"} / {@code "1,200"}.
     * Prose with no leading figure returns empty; the unit is never invented.
     *
     * @param raw source text (required)
     */
    public static Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.empty();
        }
        Matcher m = NUMBER_UNIT.matcher(raw);
        if (!m.matches()) {
            return Result.empty();
        }
        String unit = m.group(2);
        if (unit != null && SCIENTIFIC_TAIL.matcher(unit).matches()) {
            // "5.71E8": the tail is exponent notation, not a unit
            try {
                return new Result(Double.parseDouble(raw.trim().replace(",", "")), null);
            } catch (NumberFormatException e) {
                return Result.empty();
            }
        }
        try {
            double value = new BigDecimal(m.group(1).replace(",", "")).doubleValue();
            return new Result(value, unit == null ? null : KindTokens.normalizeUnit(unit), unit);
        } catch (NumberFormatException e) {
            return Result.empty();
        }
    }

    /**
     * Parsed quantity slots.
     *
     * @param valueNumber the figure
     * @param unit canonical unit key, null when the text states none
     * @param verbatimUnit the exact verbatim token from the source text
     */
    public record Result(Double valueNumber, String unit, String verbatimUnit) {

        /**
         * Constructs normalized quantity result.
         */
        public Result(Double valueNumber, String unit) {
            this(valueNumber, unit, unit);
        }

        /**
         * Returns an empty normalization result.
         */
        public static Result empty() {
            return new Result(null, null, null);
        }
    }
}
