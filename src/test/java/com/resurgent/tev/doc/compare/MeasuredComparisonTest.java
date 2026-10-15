package com.resurgent.tev.doc.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Spec (issue #135): quantities and rates compare only in the same stated
 * unit; anything else refuses as {@link Verdict#NOT_COMPARABLE} rather than
 * producing a false disagreement.
 */
class MeasuredComparisonTest {

    /** left value, left unit, right value, right unit, expected verdict. */
    record Case(Double left, String leftUnit, Double right, String rightUnit, Verdict expected) {}

    static Stream<Case> quantityTable() {
        return Stream.of(
                new Case(1200.0, "kWp", 1205.0, "kWp", Verdict.AGREE),
                new Case(1200.0, "kWp", 1300.0, "kWp", Verdict.DISAGREE),
                // tolerance edges from the stated ~1% rule
                new Case(100.0, "acre", 101.0, "acre", Verdict.AGREE),
                new Case(100.0, "acre", 102.0, "acre", Verdict.DISAGREE),
                // unit tokens compare case-insensitively
                new Case(1200.0, "kWp", 1200.5, "kwp", Verdict.AGREE),
                // different or unstated units refuse rather than compare
                new Case(1200.0, "kWp", 1.2, "MWp", Verdict.NOT_COMPARABLE),
                new Case(1200.0, "kWp", 1205.0, null, Verdict.NOT_COMPARABLE),
                new Case(1200.0, "kWp", 1205.0, " ", Verdict.NOT_COMPARABLE),
                new Case(1200.0, null, 1205.0, null, Verdict.NOT_COMPARABLE),
                new Case(null, "kWp", 1205.0, "kWp", Verdict.NOT_COMPARABLE));
    }

    @ParameterizedTest
    @MethodSource("quantityTable")
    void quantitiesCompareOnlyInTheSameUnit(Case c) {
        assertThat(Comparisons.quantity(c.left(), c.leftUnit(), c.right(), c.rightUnit()).verdict())
                .isEqualTo(c.expected());
    }

    static Stream<Case> rateTable() {
        return Stream.of(
                new Case(12.0, "percent", 12.05, "percent", Verdict.AGREE),
                new Case(12.0, "percent", 13.5, "percent", Verdict.DISAGREE),
                // % p.a. and plain % are different units: refuse, never disagree
                new Case(12.0, "percent", 12.0, "percent_pa", Verdict.NOT_COMPARABLE),
                new Case(12.0, "percent", 12.0, null, Verdict.NOT_COMPARABLE));
    }

    @ParameterizedTest
    @MethodSource("rateTable")
    void ratesCompareOnlyInTheSameUnit(Case c) {
        assertThat(Comparisons.rate(c.left(), c.leftUnit(), c.right(), c.rightUnit()).verdict())
                .isEqualTo(c.expected());
    }

    @Test
    void rateTextsCompareThroughM2RateNormalizer() {
        assertThat(Comparisons.rate("12.5% p.a.", "12.45% p.a.").verdict())
                .isEqualTo(Verdict.AGREE);
        assertThat(Comparisons.rate("12% p.a.", "10% p.a.").verdict())
                .isEqualTo(Verdict.DISAGREE);
        // a tariff written per kWh, as PPAs state it
        assertThat(Comparisons.rate("₹4.15/kWh", "4.16/kWh").verdict())
                .isEqualTo(Verdict.AGREE);
        assertThat(Comparisons.rate("₹4.15/kWh", "₹5.20/kWh").verdict())
                .isEqualTo(Verdict.DISAGREE);
        // differs in unit (percent vs percent_pa), or unstated / unreadable
        assertThat(Comparisons.rate("12% p.a.", "12%").verdict())
                .isEqualTo(Verdict.NOT_COMPARABLE);
        assertThat(Comparisons.rate("12", "12").verdict())
                .isEqualTo(Verdict.NOT_COMPARABLE);
        assertThat(Comparisons.rate("as quoted", "12%").verdict())
                .isEqualTo(Verdict.NOT_COMPARABLE);
    }
}
