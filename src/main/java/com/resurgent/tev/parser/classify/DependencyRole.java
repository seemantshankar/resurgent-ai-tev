package com.resurgent.tev.parser.classify;

/**
 * How one operand participates in its formula. Taken from the operator, never from
 * a {@code Less:} prefix or from where the row sits on the sheet.
 */
enum DependencyRole {
    /** A summand of a top-level sum or net. */
    SUMMAND_PLUS,
    /** A subtracted term of a top-level net. */
    SUMMAND_MINUS,
    /** An operand of {@code *}, or the numerator of {@code /}: a driver. */
    FACTOR,
    /** The denominator of {@code /}: a driver. */
    DIVISOR,
    /** An operand of anything else — another function, a comparison, a nesting. */
    OTHER;

    boolean isSummand() {
        return this == SUMMAND_PLUS || this == SUMMAND_MINUS;
    }

    /** A driver operand can never be a cost line, only a helper. */
    boolean isDriver() {
        return this == FACTOR || this == DIVISOR;
    }
}
