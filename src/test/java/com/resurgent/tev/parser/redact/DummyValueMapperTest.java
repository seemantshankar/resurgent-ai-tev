package com.resurgent.tev.parser.redact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DummyValueMapperTest {

    @Test
    void plainDecimal_staysInSmallMagnitudeBand() {
        double dummy = DummyValueMapper.dummyNumeric(42.5, "B5");
        assertThat(dummy).isBetween(12.0, 13.5);
        assertThat(dummy).isPositive();
    }

    @Test
    void largeValue_staysInLargeMagnitudeBand() {
        double dummy = DummyValueMapper.dummyNumeric(1_500_000, "C10");
        assertThat(dummy).isGreaterThan(100_000);
    }

    @Test
    void negativeValue_staysNegative() {
        double dummy = DummyValueMapper.dummyNumeric(-2500, "D3");
        assertThat(dummy).isNegative();
    }

    @Test
    void percentStoredFraction_staysBelowOne() {
        double dummy = DummyValueMapper.dummyNumeric(0.35, "E7");
        assertThat(dummy).isBetween(0.05, 0.99);
    }

    @Test
    void sameCoordIsDeterministic() {
        double first = DummyValueMapper.dummyNumeric(99, "F1");
        double second = DummyValueMapper.dummyNumeric(99, "F1");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentCoordsCanDiffer() {
        double a = DummyValueMapper.dummyNumeric(99, "G1");
        double b = DummyValueMapper.dummyNumeric(99, "G2");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void currencyText_preservesRupeePrefixAndIndianGrouping() {
        String dummy = DummyValueMapper.dummyAmountText("₹10,00,000", "H1");
        assertThat(dummy).startsWith("₹");
        assertThat(dummy).contains(",");
        assertThat(dummy).doesNotContain("10,00,000");
    }

    @Test
    void percentText_preservesPercentSuffix() {
        String dummy = DummyValueMapper.dummyAmountText("35%", "I1");
        assertThat(dummy).endsWith("%");
        assertThat(dummy).doesNotContain("35");
    }

    @Test
    void parenthesizedNegative_preservesAccountingStyle() {
        String dummy = DummyValueMapper.dummyAmountText("(1,234.56)", "J1");
        assertThat(dummy).startsWith("(");
        assertThat(dummy).endsWith(")");
    }

    @Test
    void plainDecimalFormat_vsPercentFormat_useDifferentBands() {
        double decimalDummy = DummyValueMapper.dummyNumeric(0.12, "K1");
        double percentStoredDummy = DummyValueMapper.dummyNumeric(0.12, "K2");
        assertThat(decimalDummy).isBetween(0.05, 0.99);
        assertThat(percentStoredDummy).isBetween(0.05, 0.99);
    }
}
