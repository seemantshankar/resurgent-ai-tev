package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DateNormalizerTest {

    @Test
    void dmySlashIsDayFirstWhenDayStated() {
        DateNormalizer.Result r = DateNormalizer.parse("31.03.2026");
        assertThat(r.valueDate()).isEqualTo("2026-03-31");
        assertThat(r.precise()).isTrue();
    }

    @Test
    void dmySlashTwelveMarch() {
        DateNormalizer.Result r = DateNormalizer.parse("12/03/2026");
        assertThat(r.valueDate()).isEqualTo("2026-03-12");
    }

    @Test
    void dmyMonthName() {
        DateNormalizer.Result r = DateNormalizer.parse("12-Mar-2026");
        assertThat(r.valueDate()).isEqualTo("2026-03-12");
    }

    @Test
    void monthOnlyNeverGuessesDay() {
        DateNormalizer.Result r = DateNormalizer.parse("March 2026");
        assertThat(r.valueDate()).isNull();
        assertThat(r.ok()).isTrue();
        assertThat(r.precise()).isFalse();
    }

    @Test
    void isoPassthrough() {
        assertThat(DateNormalizer.parse("2026-03-31").valueDate()).isEqualTo("2026-03-31");
    }

    @Test
    void financialYearIsTextOnly() {
        DateNormalizer.Result r = DateNormalizer.parse("FY 2025-26");
        assertThat(r.valueDate()).isNull();
        assertThat(r.ok()).isTrue();
    }
}
