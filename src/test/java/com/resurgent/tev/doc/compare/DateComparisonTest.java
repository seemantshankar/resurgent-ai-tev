package com.resurgent.tev.doc.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Spec (issue #135, ADR 0026 §1): dates compare exactly at the stated
 * precision. A month-precision date and a day inside it are a
 * {@link Verdict#PRECISION_MISMATCH}, not a disagreement. Different as-on
 * dates or periods are {@link Verdict#DIFFERENT_READINGS}, never a
 * disagreement — history is not contradiction.
 */
class DateComparisonTest {

    /** left raw text, right raw text, expected verdict. */
    record Case(String left, String right, Verdict expected) {}

    static Stream<Case> dateTable() {
        return Stream.of(
                // same day, stated two ways
                new Case("2025-03-15", "15 March 2025", Verdict.AGREE),
                new Case("31.03.2025", "31 March 2025", Verdict.AGREE),
                // dates are never disagreements
                new Case("15 March 2025", "16 March 2025", Verdict.DIFFERENT_READINGS),
                // the ticket's net-worth example: different as-on dates
                new Case("31 Mar 2024", "31 Mar 2025", Verdict.DIFFERENT_READINGS),
                // month precision against a day in the same month
                new Case("March 2025", "15 March 2025", Verdict.PRECISION_MISMATCH),
                new Case("March 2025", "March 2025", Verdict.AGREE),
                // month precision against another month
                new Case("March 2025", "April 2025", Verdict.DIFFERENT_READINGS),
                new Case("March 2025", "15 April 2025", Verdict.DIFFERENT_READINGS),
                // periods: financial years
                new Case("FY 2025-26", "FY 2025-26", Verdict.AGREE),
                new Case("FY 2024-25", "FY 2025-26", Verdict.DIFFERENT_READINGS),
                // the last month of a financial year is inside its period
                new Case("March 2025", "FY 2024-25", Verdict.PRECISION_MISMATCH),
                new Case("15 March 2026", "FY 2025-26", Verdict.PRECISION_MISMATCH),
                // unparseable or calendar-invalid text refuses
                new Case("in process", "15 March 2025", Verdict.NOT_COMPARABLE),
                new Case("n/a", null, Verdict.NOT_COMPARABLE),
                new Case("2026-02-31", "28 February 2026", Verdict.NOT_COMPARABLE));
    }

    @ParameterizedTest
    @MethodSource("dateTable")
    void datesCompareAtStatedPrecision(Case c) {
        assertThat(Comparisons.date(c.left(), c.right()).verdict()).isEqualTo(c.expected());
    }
}
