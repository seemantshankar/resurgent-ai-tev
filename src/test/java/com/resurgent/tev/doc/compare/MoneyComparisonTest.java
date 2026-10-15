package com.resurgent.tev.doc.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Spec (issue #135, ADR 0026 §1): money compares the absolute amount in the same
 * currency within about 1% relative tolerance after scale normalisation
 * (lakh / crore / million). Different currencies refuse rather than compare.
 */
class MoneyComparisonTest {

    /** left raw text, right raw text, expected verdict. */
    record Case(String left, String right, Verdict expected) {}

    static Stream<Case> moneyTable() {
        return Stream.of(
                new Case("₹48 cr", "₹48.3 cr", Verdict.AGREE),
                new Case("₹48 cr", "₹52.6 cr", Verdict.DISAGREE),
                // Scale normalisation: 4800 lakh IS 48 crore.
                new Case("4800 lakh", "₹48 crore", Verdict.AGREE),
                new Case("₹4.8 crore", "480 lakh", Verdict.AGREE),
                // A factor of ten is not rounding: 480 lakh is 4.8 crore, not 48.
                new Case("480 lakh", "₹48 crore", Verdict.DISAGREE),
                // Same INR stated two ways, one side via Indian digit grouping only.
                new Case("Rs. 48,00,00,000", "₹48 crore", Verdict.AGREE),
                // Tolerance edges from the stated ~1% rule: 1% passes, 2% fails.
                new Case("₹100", "₹101", Verdict.AGREE),
                new Case("₹100", "₹102", Verdict.DISAGREE),
                // Unstated currency on one side is not a differing currency.
                new Case("4.8 crore", "₹4.81 crore", Verdict.AGREE));
    }

    @ParameterizedTest
    @MethodSource("moneyTable")
    void moneyComparesAbsoluteAmountWithinTolerance(Case c) {
        assertThat(Comparisons.money(c.left(), c.right()).verdict()).isEqualTo(c.expected());
    }

    @Test
    void differentStatedCurrenciesRefuseRatherThanDisagree() {
        assertThat(Comparisons.money("₹48 cr", "48 crore (USD)").verdict())
                .isEqualTo(Verdict.NOT_COMPARABLE);
    }

    @Test
    void unreadableMoneyRefusesRatherThanDisagrees() {
        assertThat(Comparisons.money("as per annexure", "₹48 cr").verdict())
                .isEqualTo(Verdict.NOT_COMPARABLE);
        assertThat(Comparisons.money("₹48 cr", null).verdict())
                .isEqualTo(Verdict.NOT_COMPARABLE);
    }
}
