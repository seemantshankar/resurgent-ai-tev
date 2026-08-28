package com.resurgent.tev.parser.redact;

import com.resurgent.tev.parser.ingest.CellNormalizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, format-aware dummy values for redacted exports (ADR 0008).
 * Same coordinate and original magnitude band always yield the same dummy.
 */
public final class DummyValueMapper {

    private static final Pattern CURRENCY_PREFIX = Pattern.compile(
            "^(?i)((?:\\p{Sc}|Rs\\.?|INR|USD|EUR|GBP)\\s*)");
    private static final Pattern CURRENCY_SUFFIX = Pattern.compile(
            "(?i)(\\s*(?:\\p{Sc}|Rs\\.?|INR|USD|EUR|GBP))$");

    private DummyValueMapper() {
    }

    /**
     * Maps a typed numeric literal to a dummy value in a similar magnitude band.
     * Negatives stay negative.
     */
    public static double dummyNumeric(double original, String coord) {
        boolean negative = original < 0;
        double abs = Math.abs(original);
        double dummy = bandValue(abs, coord);
        return negative ? -dummy : dummy;
    }

    /**
     * Maps amount-like text (currency, percent, grouped numbers) to dummy text that
     * preserves the original visual style.
     */
    public static String dummyAmountText(String original, String coord) {
        String trimmed = original.trim();
        BigDecimal parsed = CellNormalizer.coerceNumericText(trimmed);
        if (parsed == null) {
            return original;
        }
        double dummy = dummyNumeric(parsed.doubleValue(), coord);
        return formatAmountText(trimmed, dummy);
    }

    static String formatAmountText(String original, double dummyValue) {
        String trimmed = original.trim();
        boolean parenNegative = trimmed.startsWith("(") && trimmed.endsWith(")");
        String work = parenNegative ? trimmed.substring(1, trimmed.length() - 1).trim() : trimmed;

        String prefix = "";
        Matcher prefixMatcher = CURRENCY_PREFIX.matcher(work);
        if (prefixMatcher.find()) {
            prefix = prefixMatcher.group(1);
            work = work.substring(prefixMatcher.end());
        }
        String suffix = "";
        Matcher suffixMatcher = CURRENCY_SUFFIX.matcher(work);
        if (suffixMatcher.find()) {
            suffix = suffixMatcher.group(1);
            work = work.substring(0, suffixMatcher.start()).trim();
        }

        boolean percent = work.endsWith("%");
        if (percent) {
            work = work.substring(0, work.length() - 1).trim();
        }

        boolean indianGrouping = usesIndianGrouping(work);
        BigDecimal display = percent
                ? BigDecimal.valueOf(Math.abs(dummyValue) * 100)
                : BigDecimal.valueOf(Math.abs(dummyValue));
        String number = formatGroupedNumber(display, indianGrouping);
        if (percent) {
            number = number + "%";
        }

        String result = prefix + number + suffix;
        if (dummyValue < 0) {
            result = parenNegative ? "(" + result + ")" : "-" + result;
        }
        return result;
    }

    private static double bandValue(double abs, String coord) {
        int jitter = Math.floorMod(coord.hashCode(), 89) + 10;
        if (abs == 0) {
            return 12.34 + jitter / 100.0;
        }
        if (abs < 1) {
            return (jitter % 80 + 5) / 100.0;
        }
        if (abs < 100) {
            return 12.34 + jitter / 100.0;
        }
        if (abs < 10_000) {
            return 123.45 + jitter / 10.0;
        }
        if (abs < 1_000_000) {
            return 12_345.67 + jitter;
        }
        return 123_456.78 + jitter * 10;
    }

    private static boolean usesIndianGrouping(String work) {
        if (!work.contains(",")) {
            return false;
        }
        String integerPart = work.contains(".") ? work.substring(0, work.indexOf('.')) : work;
        String[] groups = integerPart.split(",");
        if (groups.length < 2) {
            return false;
        }
        String last = groups[groups.length - 1];
        if (!last.matches("\\d{3}")) {
            return false;
        }
        for (int i = 1; i < groups.length - 1; i++) {
            if (!groups[i].matches("\\d{2}")) {
                return false;
            }
        }
        return groups[0].matches("\\d{1,2}");
    }

    private static String formatGroupedNumber(BigDecimal value, boolean indian) {
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        String plain = rounded.toPlainString();
        int dot = plain.indexOf('.');
        String intPart = dot >= 0 ? plain.substring(0, dot) : plain;
        String fracPart = dot >= 0 ? plain.substring(dot) : "";

        if (indian) {
            return formatIndian(intPart) + fracPart;
        }
        return formatWestern(intPart) + fracPart;
    }

    private static String formatWestern(String intPart) {
        if (intPart.length() <= 3) {
            return intPart;
        }
        StringBuilder sb = new StringBuilder();
        int len = intPart.length();
        int firstGroup = len % 3;
        if (firstGroup == 0) {
            firstGroup = 3;
        }
        sb.append(intPart, 0, firstGroup);
        for (int i = firstGroup; i < len; i += 3) {
            sb.append(',').append(intPart, i, i + 3);
        }
        return sb.toString();
    }

    private static String formatIndian(String intPart) {
        if (intPart.length() <= 3) {
            return intPart;
        }
        String lastThree = intPart.substring(intPart.length() - 3);
        String rest = intPart.substring(0, intPart.length() - 3);
        StringBuilder sb = new StringBuilder();
        while (rest.length() > 2) {
            sb.insert(0, rest.substring(rest.length() - 2) + ",");
            rest = rest.substring(0, rest.length() - 2);
        }
        if (!rest.isEmpty()) {
            sb.insert(0, rest + ",");
        }
        return sb + lastThree;
    }
}
