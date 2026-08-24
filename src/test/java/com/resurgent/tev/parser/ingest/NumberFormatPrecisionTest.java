package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Internal seam for source number-format precision. No global monetary epsilon.
 */
class NumberFormatPrecisionTest {

    @Test
    void missingFormat_requiresExactDecimalEquality() {
        assertThat(NumberFormatPrecision.agree(
                new BigDecimal("150.0"), new BigDecimal("150.00"), null)).isTrue();
        assertThat(NumberFormatPrecision.agree(
                new BigDecimal("1.234"), new BigDecimal("1.2340001"), null)).isFalse();
        assertThat(NumberFormatPrecision.scale(null)).isNull();
        assertThat(NumberFormatPrecision.scale("General")).isNull();
    }

    @Test
    void explicitScale_roundsBothSidesWithoutGlobalTolerance() {
        assertThat(NumberFormatPrecision.scale("0.00")).isEqualTo(2);
        assertThat(NumberFormatPrecision.scale("#,##0.00")).isEqualTo(2);
        assertThat(NumberFormatPrecision.scale("#,##0")).isEqualTo(0);
        assertThat(NumberFormatPrecision.agree(
                new BigDecimal("1.234"), new BigDecimal("1.226"), "0.00")).isTrue();
        assertThat(NumberFormatPrecision.agree(
                new BigDecimal("1.234"), new BigDecimal("1.235"), "0.00")).isFalse();
    }
}
