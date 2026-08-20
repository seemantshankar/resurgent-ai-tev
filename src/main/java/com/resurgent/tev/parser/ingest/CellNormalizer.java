package com.resurgent.tev.parser.ingest;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic cell-value normalization shared across format adapters.
 *
 * <p>The classification order follows parser-strategy-v2 §6:
 * empty → bool before numeric → exact errors → numeric text coercion
 * (commas, Indian grouping, currency, parentheses negatives, percent text)
 * with {@code coerced_from_text=true} → quantity text with structured JSON
 * → plain text.
 */
public final class CellNormalizer {

    private CellNormalizer() {
    }

    private static final Set<String> ERROR_LITERALS = Set.of(
            "#REF!", "#VALUE!", "#DIV/0!", "#NAME?", "#NUM!", "#NULL!", "#N/A");

    private static final Pattern CURRENCY_PREFIX = Pattern.compile(
            "^(?i)(?:\\p{Sc}|Rs\\.?|INR|USD|EUR|GBP)\\s*");
    private static final Pattern CURRENCY_SUFFIX = Pattern.compile(
            "\\s*(?i)(?:\\p{Sc}|Rs\\.?|INR|USD|EUR|GBP)$");

    private static final Pattern QUANTITY_NUMBER_UNIT = Pattern.compile(
            "^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(.+?)\\s*$");

    private static final Set<String> UNIT_ONLY_TOKENS = Set.of(
            "L S", "LS", "L.S.", "L/S", "PC", "SET", "NO", "NOS", "QTY");

    /**
     * Normalize a non-date, non-formula literal value.
     */
    public static CellValue normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return empty();
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return empty();
        }

        Boolean bool = parseBool(trimmed);
        if (bool != null) {
            return new CellValue("bool", "bool", trimmed, trimmed,
                    null, bool, null, false, null, false, null);
        }

        if (ERROR_LITERALS.contains(trimmed)) {
            return new CellValue("error", "error", trimmed, trimmed,
                    null, null, null, false, null, true, trimmed);
        }

        BigDecimal numeric = coerceNumericText(trimmed);
        if (numeric != null) {
            return new CellValue("number", "number", trimmed, trimmed,
                    numeric, null, null, true, null, false, null);
        }

        ParsedQuantity quantity = parseQuantity(trimmed);
        if (quantity != null) {
            return new CellValue("text", "quantity_text", trimmed, trimmed,
                    null, null, null, false, quantity, false, null);
        }

        return new CellValue("text", "text", trimmed, trimmed,
                null, null, null, false, null, false, null);
    }

    /**
     * Normalize a date cell. The original serial string is preserved by the
     * adapter as {@code raw_value}; this method only produces the typed value.
     */
    public static CellValue normalizeDate(LocalDateTime dateTime) {
        return new CellValue("date", "date", null, null,
                null, null, dateTime, false, null, false, null);
    }

    private static CellValue empty() {
        return new CellValue("empty", "empty", null, null,
                null, null, null, false, null, false, null);
    }

    private static Boolean parseBool(String trimmed) {
        if (trimmed.equalsIgnoreCase("TRUE")) {
            return Boolean.TRUE;
        }
        if (trimmed.equalsIgnoreCase("FALSE")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Attempt to coerce text that looks like a number. Handles commas
     * (including Indian grouping), common currency symbols/abbreviations,
     * parentheses negatives, and percent text.
     */
    static BigDecimal coerceNumericText(String trimmed) {
        String work = trimmed;

        boolean negative = false;
        if (work.startsWith("(") && work.endsWith(")")) {
            negative = true;
            work = work.substring(1, work.length() - 1).trim();
        }

        work = CURRENCY_PREFIX.matcher(work).replaceFirst("");
        work = CURRENCY_SUFFIX.matcher(work).replaceFirst("");
        if (!hasValidGrouping(work)) {
            return null;
        }
        work = work.replace(",", "").trim();

        boolean percent = false;
        if (work.endsWith("%")) {
            percent = true;
            work = work.substring(0, work.length() - 1).trim();
        }

        if (work.isEmpty()) {
            return null;
        }

        BigDecimal value;
        try {
            value = new BigDecimal(work);
        } catch (NumberFormatException e) {
            return null;
        }

        if (negative) {
            value = value.negate();
        }
        if (percent) {
            value = value.divide(BigDecimal.valueOf(100), MathContext.DECIMAL128);
        }
        return value;
    }

    /**
     * Validates Western (3-digit groups) or Indian (2-digit groups after the
     * rightmost 3-digit group) comma placement. Invalid groupings such as
     * {@code 1,00,00} are rejected so they do not silently coerce.
     */
    private static boolean hasValidGrouping(String work) {
        if (!work.contains(",")) {
            return true;
        }
        String integerPart = work.contains(".") ? work.substring(0, work.indexOf('.')) : work;
        String[] groups = integerPart.split(",");
        String last = groups[groups.length - 1];
        if (!last.matches("\\d{3}")) {
            return false;
        }
        boolean western = true;
        for (int i = 1; i < groups.length - 1; i++) {
            if (!groups[i].matches("\\d{3}")) {
                western = false;
                break;
            }
        }
        if (western && groups[0].matches("\\d{1,3}")) {
            return true;
        }
        boolean indian = true;
        for (int i = 1; i < groups.length - 1; i++) {
            if (!groups[i].matches("\\d{2}")) {
                indian = false;
                break;
            }
        }
        return indian && groups[0].matches("\\d{1,2}");
    }

    private static ParsedQuantity parseQuantity(String trimmed) {
        String commaStripped = trimmed.replace(",", "");
        Matcher m = QUANTITY_NUMBER_UNIT.matcher(commaStripped);
        if (m.matches()) {
            BigDecimal count = new BigDecimal(m.group(1));
            String unit = m.group(2).trim();
            return new ParsedQuantity(count, unit, trimmed);
        }

        if (UNIT_ONLY_TOKENS.contains(trimmed.toUpperCase())) {
            return new ParsedQuantity(null, trimmed, trimmed);
        }

        return null;
    }
}
