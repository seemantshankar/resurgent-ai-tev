package com.resurgent.tev.doc.compare;

/**
 * A comparison outcome: the {@link Verdict} plus a short human-readable
 * reason (which amounts, which units, why it refused), suitable for storing
 * on a conflict or review-queue row.
 *
 * @param verdict the outcome (required)
 * @param detail one-line explanation of how the verdict was reached
 */
public record Comparison(Verdict verdict, String detail) {

    public static Comparison agree(String detail) {
        return new Comparison(Verdict.AGREE, detail);
    }

    public static Comparison disagree(String detail) {
        return new Comparison(Verdict.DISAGREE, detail);
    }

    public static Comparison differentReadings(String detail) {
        return new Comparison(Verdict.DIFFERENT_READINGS, detail);
    }

    public static Comparison precisionMismatch(String detail) {
        return new Comparison(Verdict.PRECISION_MISMATCH, detail);
    }

    public static Comparison notComparable(String detail) {
        return new Comparison(Verdict.NOT_COMPARABLE, detail);
    }

    /** True when the two values state the same thing. */
    public boolean agrees() {
        return verdict == Verdict.AGREE;
    }

    /** True when the two values are comparable and state different things. */
    public boolean differs() {
        return verdict == Verdict.DISAGREE;
    }
}
