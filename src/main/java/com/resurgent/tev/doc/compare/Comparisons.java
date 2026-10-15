package com.resurgent.tev.doc.compare;

import com.resurgent.tev.doc.extract.MoneyNormalizer;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one pure, deterministic comparison module every M3 check uses to decide
 * whether two values agree (issue #135; ADR 0026 §1). No LLM, no DB, no
 * network: inputs are the strings / numbers already stored on facts, clause
 * slots and cell readings; outputs are a {@link Verdict} plus a reason.
 *
 * <p>Rules:
 * <ul>
 *   <li><b>Money</b>: absolute amount in the same currency, within
 *       {@link #RELATIVE_TOLERANCE} relative tolerance, after scale
 *       normalisation ({@link MoneyNormalizer} — its multipliers are the same
 *       ones guarded against {@code CellScale} by {@code MoneyNormalizerTest}).</li>
 *   <li><b>Quantities and rates</b>: numbers compare only in the same stated
 *       unit; anything else refuses as {@link Verdict#NOT_COMPARABLE} rather
 *       than producing a false disagreement.</li>
 *   <li><b>Dates</b>: exact at the stated precision (see {@link #date}).</li>
 *   <li><b>Names</b>: loose — case, punctuation, whitespace and legal-suffix
 *       equivalences.</li>
 *   <li><b>Sites</b>: loose score, reported so ambiguous matches can be
 *       escalated to the LLM by later tickets.</li>
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
        BigDecimal l = new BigDecimal(left.absoluteAmount());
        BigDecimal r = new BigDecimal(right.absoluteAmount());
        BigDecimal diff = l.subtract(r).abs();
        BigDecimal max = l.abs().max(r.abs());
        if (max.signum() == 0) {
            return Comparison.agree("both are zero");
        }
        BigDecimal threshold = max.multiply(BigDecimal.valueOf(RELATIVE_TOLERANCE));
        if (diff.compareTo(threshold) <= 0) {
            return Comparison.agree(describe(l, r, diff, max) + " within "
                    + percent(RELATIVE_TOLERANCE) + " tolerance");
        }
        return Comparison.disagree(describe(l, r, diff, max) + " apart");
    }

    private static String describe(BigDecimal l, BigDecimal r, BigDecimal diff, BigDecimal max) {
        return l.toPlainString() + " vs " + r.toPlainString() + " ("
                + diff.multiply(BigDecimal.valueOf(100)).divide(max, 2, RoundingMode.HALF_UP)
                        .stripTrailingZeros().toPlainString()
                + "% apart)";
    }

    private static String percent(double fraction) {
        return BigDecimal.valueOf(fraction).multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros().toPlainString() + "%";
    }

    private static String quote(String value) {
        return value == null ? "null" : "'" + value.trim() + "'";
    }
}
