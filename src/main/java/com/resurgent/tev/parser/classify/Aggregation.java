package com.resurgent.tev.parser.classify;

import java.util.List;

/**
 * A head cell whose formula sums or nets its members, with membership read from the
 * formula rather than guessed from layout: {@code SUM(J33:J54)} <em>is</em> the
 * operating-cost group. Sign comes from the operator, so a subtracted term is a
 * deduct without any string matching.
 */
record Aggregation(
        long headCellId,
        long worksheetId,
        String relativeSignature,
        String headLabel,
        List<Member> members) {

    Aggregation {
        members = members == null ? List.of() : List.copyOf(members);
    }

    /** One member cell and the sign its operator gave it. */
    record Member(long cellId, boolean plus, String label) {

        String sign() {
            return plus ? AggregationMemberRow.SIGN_PLUS : AggregationMemberRow.SIGN_MINUS;
        }

        String amountRole() {
            return plus ? AmountRole.ADD : AmountRole.DEDUCT;
        }
    }
}
