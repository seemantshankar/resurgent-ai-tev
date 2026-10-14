package com.resurgent.tev.doc.extract;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Indian and international date parsing (Architecture §7.4).
 * Precise dates become ISO {@code value_date}; month-only stays text with
 * {@code value_date=null}. Never guesses a day.
 */
public final class DateNormalizer {

    private static final Pattern DMY_SLASH = Pattern.compile(
            "^(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4})$");
    private static final Pattern DMY_MONTH = Pattern.compile(
            "^(\\d{1,2})[\\s\\-]+([A-Za-z]{3,9})[\\s\\-]+(\\d{4})$");
    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern YEAR_MONTH = Pattern.compile(
            "^(?:(\\d{1,2})[\\s\\-]+)?([A-Za-z]{3,9})[\\s,]+(\\d{4})$");
    private static final Pattern FY = Pattern.compile(
            "^FY\\s*(\\d{4})\\s*[-/]\\s*(\\d{2,4})$", Pattern.CASE_INSENSITIVE);

    private DateNormalizer() {}

    /**
     * Parses a date string.
     *
     * @param raw source text (required, may be blank)
     * @return ISO date when precise, or text-only when month/year grain
     */
    public static Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.empty();
        }
        String text = raw.trim();
        Matcher iso = ISO.matcher(text);
        if (iso.matches()) {
            return new Result(iso.group(1) + "-" + iso.group(2) + "-" + iso.group(3),
                    text, true, true);
        }
        Matcher dmy = DMY_SLASH.matcher(text);
        if (dmy.matches()) {
            int day = Integer.parseInt(dmy.group(1));
            int month = Integer.parseInt(dmy.group(2));
            if (day > 12 && month <= 12) {
                // 31/03/2026 style is day-first
                return iso(day, month, Integer.parseInt(dmy.group(3)), text);
            }
            // ambiguous 12/03/2026 — India commonly day-first; still refuse if both > 12
            if (day > 12 && month > 12) {
                return new Result(null, text, false, true);
            }
            return iso(day, month, Integer.parseInt(dmy.group(3)), text);
        }
        Matcher dmyMonth = DMY_MONTH.matcher(text);
        if (dmyMonth.matches()) {
            int day = Integer.parseInt(dmyMonth.group(1));
            Integer month = monthNumber(dmyMonth.group(2));
            if (month == null) {
                return new Result(null, text, false, true);
            }
            return iso(day, month, Integer.parseInt(dmyMonth.group(3)), text);
        }
        Matcher ym = YEAR_MONTH.matcher(text);
        if (ym.matches()) {
            Integer month = monthNumber(ym.group(2));
            int year = Integer.parseInt(ym.group(3));
            if (ym.group(1) != null && month != null) {
                return iso(Integer.parseInt(ym.group(1)), month, year, text);
            }
            // month-only or month-year: never invent a day
            return new Result(null, text, true, false);
        }
        Matcher fy = FY.matcher(text);
        if (fy.matches()) {
            return new Result(null, text, true, false);
        }
        return new Result(null, text, false, true);
    }

    private static Result iso(int day, int month, int year, String raw) {
        if (day < 1 || day > 31 || month < 1 || month > 12 || year < 1900 || year > 2100) {
            return new Result(null, raw, false, true);
        }
        return new Result(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day),
                raw, true, true);
    }

    private static Integer monthNumber(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return switch (n) {
            case "jan", "january" -> 1;
            case "feb", "february" -> 2;
            case "mar", "march" -> 3;
            case "apr", "april" -> 4;
            case "may" -> 5;
            case "jun", "june" -> 6;
            case "jul", "july" -> 7;
            case "aug", "august" -> 8;
            case "sep", "sept", "september" -> 9;
            case "oct", "october" -> 10;
            case "nov", "november" -> 11;
            case "dec", "december" -> 12;
            default -> null;
        };
    }

    /**
     * Parsed date slots.
     *
     * @param valueDate ISO date when precise, else null
     * @param valueText display / raw text
     * @param ok true when the input was recognized as a date
     * @param precise true when a day was stated
     */
    public record Result(String valueDate, String valueText, boolean ok, boolean precise) {

        public static Result empty() {
            return new Result(null, null, false, false);
        }
    }
}
