package com.resurgent.tev.parser.classify;

/**
 * One member of an aggregation, with the sign taken from the formula operator
 * rather than from a {@code Less:} prefix, and the role that sign implies.
 */
public record AggregationMemberRow(
        int ordinal,
        long memberCellId,
        String sign,
        String amountRole,
        String memberLabel) {

    public static final String SIGN_PLUS = "plus";
    public static final String SIGN_MINUS = "minus";
}
