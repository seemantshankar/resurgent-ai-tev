package com.resurgent.tev.doc.compare;

import com.resurgent.tev.doc.extract.MoneyNormalizer;
import com.resurgent.tev.doc.extract.RateNormalizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

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
