package com.resurgent.tev.doc.compare;

import com.resurgent.tev.doc.extract.DateNormalizer;
import com.resurgent.tev.doc.extract.MoneyNormalizer;
import com.resurgent.tev.doc.extract.RateNormalizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one pure, deterministic comparison module every M3 check uses to decide
 * whether two values agree (issue #135; ADR 0026 §1). No LLM, no DB, no
 * network: inputs are the strings / numbers already stored on facts, clause
 * slots and cell readings; outputs are a {@link Verdict} plus a reason.
 *
 * <p>Rules:
 * <ul>
 *   <li><b>Money</b> ({@link #money}): absolute amount in the same currency,
 *       within {@link #RELATIVE_TOLERANCE} relative tolerance, after scale
 *       normalisation ({@link MoneyNormalizer} — its multipliers are the same
 *       ones guarded against {@code CellScale} by {@code MoneyNormalizerTest}).</li>
 *   <li><b>Quantities</b> ({@link #quantity}) and <b>rates</b> ({@link #rate}):
 *       numbers compare only in the same stated unit; anything else refuses as
 *       {@link Verdict#NOT_COMPARABLE} rather than producing a false
 *       disagreement.</li>
 *   <li><b>Dates</b> ({@link #date}): exact at the stated precision.</li>
 *   <li><b>Names</b> ({@link #name}): loose — case, punctuation, whitespace
 *       and legal-suffix equivalences.</li>
 *   <li><b>Sites</b> ({@link #site}): loose score, reported so ambiguous
 *       matches can be escalated to the LLM by later tickets.</li>
 * </ul>
 */
public final class Comparisons {

    /**
     * The "about 1%" relative tolerance (ADR 0026 §1) applied to money,
     * quantity and rate magnitudes: {@code |a - b| <= tolerance × max(|a|, |b|)}.
     */
    public static final double RELATIVE_TOLERANCE = 0.01;

    /**
     * Legal forms stripped from the end of a name before loose comparison:
     * {@code Pvt Ltd} / {@code Private Limited} / {@code Ltd} / {@code Limited}
     * / {@code LLP} (issue #135).
     */
    private static final Set<String> LEGAL_FORMS = Set.of("pvt", "private", "ltd", "limited", "llp");

    private Comparisons() {}

    /**
     * Compares two money statements after scale normalisation.
     *
     * <p>Both sides are parsed with M2's {@link MoneyNormalizer}, so
     * {@code ₹48 cr} and {@code 4800 lakh} both become absolute amounts and
     * agree. Refuses ({@link Verdict#NOT_COMPARABLE}) when either side is not
     * a money amount, or when both sides state currencies and they differ.
     * A currency stated on only one side is not a differing currency (the
     * other side simply did not say), so the amounts still compare.
     *
     * @param leftRaw left money text as stated (e.g. {@code "₹48.3 cr"})
     * @param rightRaw right money text as stated
     * @return {@link Verdict#AGREE} within tolerance, {@link Verdict#DISAGREE}
     *         beyond it, {@link Verdict#NOT_COMPARABLE} on refusal
     */
    public static Comparison money(String leftRaw, String rightRaw) {
        MoneyNormalizer.Result left = MoneyNormalizer.parse(leftRaw);
        MoneyNormalizer.Result right = MoneyNormalizer.parse(rightRaw);
        if (!left.ok() || !right.ok()) {
            return Comparison.notComparable(
                    "cannot parse money: " + quote(leftRaw) + " vs " + quote(rightRaw));
        }
        if (left.currency() != null && right.currency() != null
                && !left.currency().equalsIgnoreCase(right.currency())) {
            return Comparison.notComparable(
                    "different currencies: " + left.currency() + " vs " + right.currency());
        }
        return compareMagnitudes(new BigDecimal(left.absoluteAmount()),
                new BigDecimal(right.absoluteAmount()), "");
    }

    /**
     * Compares two quantities in the same unit within
     * {@link #RELATIVE_TOLERANCE}. The unit is never converted: a different
     * or unstated unit on either side refuses as
     * {@link Verdict#NOT_COMPARABLE}, so 1200 kWp vs 1.2 MWp is not a
     * disagreement. Unit tokens compare case-insensitively and trimmed.
     */
    public static Comparison quantity(
            Double leftValue, String leftUnit, Double rightValue, String rightUnit) {
        return measured("quantity", leftValue, leftUnit, rightValue, rightUnit);
    }

    /**
     * Structured rate comparison — identical rules to {@link #quantity}: same
     * stated unit (e.g. {@code "percent"}, {@code "percent_pa"},
     * {@code "kwh"}) or refuse. Never invents a unit.
     */
    public static Comparison rate(
            Double leftValue, String leftUnit, Double rightValue, String rightUnit) {
        return measured("rate", leftValue, leftUnit, rightValue, rightUnit);
    }

    /**
     * Compares two rate texts through M2's {@link RateNormalizer}
     * ({@code "12.5% p.a."}, {@code "₹4.15/kWh"}). A rate whose unit the text
     * does not state refuses — the normalizer leaves the unit empty and this
     * module never invents one.
     */
    public static Comparison rate(String leftRaw, String rightRaw) {
        RateNormalizer.Result left = RateNormalizer.parse(leftRaw);
        RateNormalizer.Result right = RateNormalizer.parse(rightRaw);
        if (left.valueNumber() == null || right.valueNumber() == null) {
            return Comparison.notComparable(
                    "cannot parse rate: " + quote(leftRaw) + " vs " + quote(rightRaw));
        }
        return measured("rate", left.valueNumber(), left.unit(),
                right.valueNumber(), right.unit());
    }

    private static final Pattern FY_TEXT = Pattern.compile(
            "^FY\\s*(\\d{4})\\s*[-/]\\s*(\\d{2,4})$", Pattern.CASE_INSENSITIVE);

    /**
     * Compares two dates exactly at the stated precision, through M2's
     * {@link DateNormalizer}. Days equal → AGREE; two different dates or
     * periods → {@link Verdict#DIFFERENT_READINGS} (dates are never a
     * disagreement); a coarser statement overlapping a finer one — a month
     * and a day inside it, a financial year and a day inside it — →
     * {@link Verdict#PRECISION_MISMATCH}. Unrecognised text, or a day M2
     * recognises but no calendar has (31 Feb), refuses.
     */
    public static Comparison date(String leftRaw, String rightRaw) {
        Span left = span(DateNormalizer.parse(leftRaw));
        Span right = span(DateNormalizer.parse(rightRaw));
        if (left == null || right == null) {
            return Comparison.notComparable(
                    "cannot interpret date: " + quote(leftRaw) + " vs " + quote(rightRaw));
        }
        if (left.start().equals(right.start()) && left.end().equals(right.end())) {
            return Comparison.agree(left.text() + " = " + right.text()
                    + " (" + left.grain() + ")");
        }
        boolean overlaps = !left.end().isBefore(right.start())
                && !right.end().isBefore(left.start());
        if (overlaps) {
            return Comparison.precisionMismatch(left.text() + " (" + left.grain()
                    + ") overlaps " + right.text() + " (" + right.grain() + ")");
        }
        return Comparison.differentReadings(left.text() + " (" + left.grain()
                + ") and " + right.text() + " (" + right.grain() + ") are different readings");
    }

    /**
     * Compares two names loosely (issue #135; GLOSSARY "Entity"): case is
     * ignored, punctuation and whitespace separate nothing, and the legal
     * forms {@code Pvt Ltd} / {@code Private Limited / Ltd / Limited / LLP}
     * are equivalent. Genuinely different names are {@link Verdict#DISAGREE};
     * a blank name, or one that is nothing but a legal form, refuses.
     */
    public static Comparison name(String left, String right) {
        String l = looseNameKey(left);
        String r = looseNameKey(right);
        if (l == null || r == null) {
            return Comparison.notComparable(
                    "cannot compare names: " + quote(left) + " vs " + quote(right));
        }
        if (l.equals(r)) {
            return Comparison.agree(quote(left) + " = " + quote(right)
                    + " (loose key '" + l + "')");
        }
        return Comparison.disagree("different names: " + quote(left) + " ('" + l
                + "') vs " + quote(right) + " ('" + r + "')");
    }

    /** The comparison key of a name: tokens lower-cased, concatenated, legal forms stripped. */
    private static String looseNameKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> tokens = nameTokens(raw);
        int end = tokens.size();
        while (end > 0 && LEGAL_FORMS.contains(tokens.get(end - 1))) {
            end--;
        }
        if (end == 0) {
            return null;
        }
        return String.join("", tokens.subList(0, end));
    }

    /**
     * Lower-cased alphanumeric tokens of a name; every run of punctuation or
     * whitespace is a separator, so {@code "A.B.C. Traders"} and
     * {@code "ABC Traders"} tokenise alike.
     */
    private static List<String> nameTokens(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^\\p{Alnum}]+", " ").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return List.of(cleaned.split(" "));
    }

    /** The calendar interval a stated date covers at its own precision. */
    private record Span(LocalDate start, LocalDate end, String grain, String text) {}

    private static Span span(DateNormalizer.Result parsed) {
        if (!parsed.ok()) {
            return null;
        }
        if (parsed.precise() && parsed.valueDate() != null) {
            LocalDate day = isoDay(parsed.valueDate());
            return day == null ? null : new Span(day, day, "day", parsed.valueText());
        }
        String text = parsed.valueText();
        if (text == null) {
            return null;
        }
        // Month grain: M2 keeps "March 2025" as text and never guesses a day.
        // Re-asking the same normalizer for day 1 bounds the month without
        // inventing a day in any stored value.
        DateNormalizer.Result anchored = DateNormalizer.parse("1 " + text);
        if (anchored.precise() && anchored.valueDate() != null) {
            LocalDate first = isoDay(anchored.valueDate());
            if (first != null) {
                return new Span(first, first.withDayOfMonth(first.lengthOfMonth()),
                        "month", text);
            }
        }
        Matcher fy = FY_TEXT.matcher(text.trim());
        if (fy.matches()) {
            int startYear = Integer.parseInt(fy.group(1));
            int endYear = fiscalEndYear(startYear, fy.group(2));
            if (endYear == startYear + 1) {
                return new Span(LocalDate.of(startYear, 4, 1), LocalDate.of(endYear, 3, 31),
                        "financial year", text);
            }
        }
        // Recognised as a date by M2 but no precision interval can be built:
        // refuse rather than guess.
        return null;
    }

    /** "26" after FY 2025 means 2026; "1999" after FY 1998 means 1999. */
    private static int fiscalEndYear(int startYear, String endText) {
        int end = Integer.parseInt(endText);
        if (endText.length() <= 2) {
            return startYear - (startYear % 100) + end;
        }
        return end;
    }

    private static LocalDate isoDay(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static Comparison measured(
            String kind, Double leftValue, String leftUnit, Double rightValue, String rightUnit) {
        if (leftValue == null || rightValue == null) {
            return Comparison.notComparable(kind + " missing a value: "
                    + leftValue + " vs " + rightValue);
        }
        String lu = unitKey(leftUnit);
        String ru = unitKey(rightUnit);
        if (lu == null || ru == null) {
            return Comparison.notComparable(kind + " unit unstated: "
                    + quote(leftUnit) + " vs " + quote(rightUnit));
        }
        if (!lu.equals(ru)) {
            return Comparison.notComparable(kind + " units differ: " + lu + " vs " + ru);
        }
        return compareMagnitudes(BigDecimal.valueOf(leftValue), BigDecimal.valueOf(rightValue),
                " " + lu);
    }

    /** Unit tokens compare case-insensitively, trimmed, inner runs collapsed. */
    private static String unitKey(String unit) {
        if (unit == null || unit.isBlank()) {
            return null;
        }
        return unit.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * {@code |l - r| <= RELATIVE_TOLERANCE × max(|l|, |r|)} → AGREE, else
     * DISAGREE. {@code unitSuffix} is appended to both magnitudes in the
     * detail (empty for money).
     */
    private static Comparison compareMagnitudes(BigDecimal l, BigDecimal r, String unitSuffix) {
        String detail = display(l) + unitSuffix + " vs " + display(r) + unitSuffix;
        BigDecimal diff = l.subtract(r).abs();
        BigDecimal max = l.abs().max(r.abs());
        if (max.signum() == 0) {
            return Comparison.agree(detail + " (both zero)");
        }
        BigDecimal percent = diff.multiply(BigDecimal.valueOf(100))
                .divide(max, 2, RoundingMode.HALF_UP).stripTrailingZeros();
        String spread = detail + " (" + percent.toPlainString() + "% apart)";
        if (diff.compareTo(max.multiply(BigDecimal.valueOf(RELATIVE_TOLERANCE))) <= 0) {
            return Comparison.agree(spread + " within " + percentText(RELATIVE_TOLERANCE)
                    + " tolerance");
        }
        return Comparison.disagree(spread);
    }

    private static String display(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String percentText(double fraction) {
        return BigDecimal.valueOf(fraction).multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros().toPlainString() + "%";
    }

    private static String quote(String value) {
        return value == null ? "null" : "'" + value.trim() + "'";
    }
}
