package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DurationNormalizerTest {

    @Test
    void twentyFiveYears() {
        DurationNormalizer.Result r = DurationNormalizer.parse("25 years");
        assertThat(r.valueNumber()).isEqualTo(25.0);
        assertThat(r.unit()).isEqualTo("years");
        assertThat(r.ok()).isTrue();
    }

    @Test
    void abbreviatedYear() {
        DurationNormalizer.Result r = DurationNormalizer.parse("25 Yr");
        assertThat(r.valueNumber()).isEqualTo(25.0);
        assertThat(r.unit()).isEqualTo("years");
    }

    @Test
    void unstatedUnitIsNotOk() {
        DurationNormalizer.Result r = DurationNormalizer.parse("25");
        assertThat(r.valueNumber()).isEqualTo(25.0);
        assertThat(r.unit()).isNull();
        assertThat(r.ok()).isFalse();
    }

    @Test
    void monthsAndDays() {
        assertThat(DurationNormalizer.parse("18 months").unit()).isEqualTo("months");
        assertThat(DurationNormalizer.parse("45 days").unit()).isEqualTo("days");
    }

    @Test
    void nonDurationEmpty() {
        assertThat(DurationNormalizer.parse("n/a").valueNumber()).isNull();
        assertThat(DurationNormalizer.parse("").valueNumber()).isNull();
    }
}
