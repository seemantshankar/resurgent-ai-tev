package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoneyNormalizerTest {

    @Test
    void lakhGetsScaleAndAbsoluteAmount() {
        MoneyNormalizer.Result r = MoneyNormalizer.parse("Rs. 12.5 lakhs");
        assertThat(r.ok()).isTrue();
        assertThat(r.amount()).isEqualByComparingTo("12.5");
        assertThat(r.scale()).isEqualTo(MoneyNormalizer.Scale.LAKH);
        assertThat(r.absoluteAmount()).isEqualTo("1250000");
        assertThat(r.currency()).isEqualTo("INR");
    }

    @Test
    void croreGetsScaleAndAbsoluteAmount() {
        MoneyNormalizer.Result r = MoneyNormalizer.parse("₹ 2.45 crores");
        assertThat(r.ok()).isTrue();
        assertThat(r.scale()).isEqualTo(MoneyNormalizer.Scale.CRORE);
        assertThat(r.absoluteAmount()).isEqualTo("24500000");
        assertThat(r.currency()).isEqualTo("INR");
    }

    @Test
    void indianGroupingDigitsParse() {
        MoneyNormalizer.Result r = MoneyNormalizer.parse("Rs. 2,45,00,000");
        assertThat(r.ok()).isTrue();
        assertThat(r.scale()).isEqualTo(MoneyNormalizer.Scale.UNIT);
        assertThat(r.absoluteAmount()).isEqualTo("24500000");
    }

    @Test
    void bareNumberIsUnitScale() {
        MoneyNormalizer.Result r = MoneyNormalizer.parse("18000");
        assertThat(r.ok()).isTrue();
        assertThat(r.scale()).isEqualTo(MoneyNormalizer.Scale.UNIT);
        assertThat(r.absoluteAmount()).isEqualTo("18000");
    }

    @Test
    void millionBillionThousandMultipliers() {
        assertThat(MoneyNormalizer.parse("INR 3 million").absoluteAmount()).isEqualTo("3000000");
        assertThat(MoneyNormalizer.parse("INR 3 billions").absoluteAmount()).isEqualTo("3000000000");
        assertThat(MoneyNormalizer.parse("INR 3 thousands").absoluteAmount()).isEqualTo("3000");
        assertThat(MoneyNormalizer.parse("Rs 3 lacs").scale()).isEqualTo(MoneyNormalizer.Scale.LAKH);
    }

    @Test
    void unreadableTextIsEmpty() {
        assertThat(MoneyNormalizer.parse("n/a").ok()).isFalse();
        assertThat(MoneyNormalizer.parse("").ok()).isFalse();
        assertThat(MoneyNormalizer.parse(null).ok()).isFalse();
    }
}
