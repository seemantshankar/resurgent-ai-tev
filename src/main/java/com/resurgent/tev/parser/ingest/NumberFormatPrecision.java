package com.resurgent.tev.parser.ingest;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounds full-precision decimals to the explicit display scale of an Excel
 * number format. Absent a display scale, callers must use exact compareTo
 * unless they are comparing cost-head money, which is always two decimal places.
 */
public final class NumberFormatPrecision {

    private NumberFormatPrecision() {}

    static Integer scale(String numberFormat) {
        if (numberFormat == null || numberFormat.isBlank()) {
            return null;
        }
        String stripped = strip(numberFormat);
        if (stripped.isBlank() || stripped.equalsIgnoreCase("General")) {
            return null;
        }
        int dot = stripped.lastIndexOf('.');
        if (dot < 0) {
            for (int i = 0; i < stripped.length(); i++) {
                char ch = stripped.charAt(i);
                if (ch == '0' || ch == '#') {
                    return 0;
                }
            }
            return null;
        }
        int scale = 0;
        for (int i = dot + 1; i < stripped.length(); i++) {
            char ch = stripped.charAt(i);
            if (ch == '0' || ch == '#') {
                scale++;
            } else if (ch == '?' || ch == ',') {
                continue;
            } else {
                break;
            }
        }
        return scale;
    }

    static boolean agree(BigDecimal left, BigDecimal right, String numberFormat) {
        if (left == null || right == null) {
            return false;
        }
        Integer scale = scale(numberFormat);
        if (scale == null) {
            return left.compareTo(right) == 0;
        }
        return round(left, scale).compareTo(round(right, scale)) == 0;
    }

    /** Cost-head amounts are money: paise, not IEEE remainder. */
    public static boolean agreePaise(BigDecimal left, BigDecimal right) {
        return agree(left, right, "0.00");
    }

    static BigDecimal round(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static String strip(String format) {
        StringBuilder out = new StringBuilder();
        boolean quote = false;
        int bracket = 0;
        for (int i = 0; i < format.length(); i++) {
            char ch = format.charAt(i);
            if (quote) {
                if (ch == '"') {
                    quote = false;
                }
                continue;
            }
            if (ch == '"') {
                quote = true;
                continue;
            }
            if (ch == '[') {
                bracket++;
                continue;
            }
            if (ch == ']' && bracket > 0) {
                bracket--;
                continue;
            }
            if (bracket > 0) {
                continue;
            }
            if (ch == ';') {
                break;
            }
            out.append(ch);
        }
        return out.toString();
    }
}
