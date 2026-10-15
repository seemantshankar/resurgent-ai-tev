package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.extract.DateNormalizer;
import java.time.Month;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical document-date forms (issue #134): {@code YYYY-MM-DD} for day precision,
 * {@code YYYY-MM} for month precision, {@code YYYY} for year precision. The canonical
 * form never invents a grain the document did not state — "March 2025" stays
 * {@code 2025-03}, never a fabricated day.
 */
public final class DocumentDates {

    private static final Pattern MONTH_YEAR = Pattern.compile(
            "\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?"
                    + "|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?"
                    + "|Dec(?:ember)?)\\s*,?\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("\\b(\\d{4})\\b");

    private DocumentDates() {}

    /** A canonical date and the precision it is trustworthy at. */
    public record Canonical(String documentDate, String precision) {}

    /**
     * Canonicalizes a stated date expression through {@link DateNormalizer}.
     *
     * @param dateText the date as stated (may be null/blank)
     * @return the canonical date with its precision, or null when not a usable date
     */
    public static Canonical canonical(String dateText) {
        if (dateText == null || dateText.isBlank()) {
            return null;
        }
        DateNormalizer.Result parsed = DateNormalizer.parse(dateText);
        if (!parsed.ok()) {
            return null;
        }
        if (parsed.precise() && parsed.valueDate() != null) {
            return new Canonical(parsed.valueDate(), "day");
        }
        Matcher monthYear = MONTH_YEAR.matcher(dateText);
        if (monthYear.find()) {
            Integer month = monthNumber(monthYear.group(1));
            if (month != null) {
                return new Canonical(
                        String.format(Locale.ROOT, "%s-%02d", monthYear.group(2), month),
                        "month");
            }
        }
        Matcher year = YEAR.matcher(dateText);
        if (year.find()) {
            return new Canonical(year.group(1), "year");
        }
        return null;
    }

    /**
     * Day-precision canonical date from an ISO timestamp, e.g. the ingest-recorded file
     * modification time used as the file-metadata fallback.
     *
     * @param timestamp ISO-8601 timestamp (may be null)
     * @return the canonical date, or null when the timestamp is unusable
     */
    public static Canonical fromIsoTimestamp(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) {
            return null;
        }
        DateNormalizer.Result parsed = DateNormalizer.parse(timestamp.substring(0, 10));
        if (parsed.ok() && parsed.precise() && parsed.valueDate() != null) {
            return new Canonical(parsed.valueDate(), "day");
        }
        return null;
    }

    private static Integer monthNumber(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        if (n.equals("SEPT")) {
            n = "SEPTEMBER";
        }
        if (n.length() == 3) {
            for (Month month : Month.values()) {
                if (month.name().startsWith(n)) {
                    return month.getValue();
                }
            }
            return null;
        }
        try {
            return Month.valueOf(n).getValue();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
