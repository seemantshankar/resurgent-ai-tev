package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RateNormalizerTest {

    @Test
    void percentKeepsPercentUnit() {
        RateNormalizer.Result r = RateNormalizer.parse("18%");
        assertThat(r.valueNumber()).isEqualTo(18.0);
        assertThat(r.unit()).isEqualTo("percent");
        assertThat(r.unitStated()).isTrue();
    }

    @Test
    void statedRupeePerKwhKeepsUnit() {
        RateNormalizer.Result r = RateNormalizer.parse("₹ 4.50 / kWh");
        assertThat(r.valueNumber()).isEqualTo(4.50);
        assertThat(r.unit()).isEqualTo("kwh");
        assertThat(r.unitStated()).isTrue();
    }

    @Test
    void unstatedRateUnitIsNull() {
        RateNormalizer.Result r = RateNormalizer.parse("4.50");
        assertThat(r.valueNumber()).isEqualTo(4.50);
        assertThat(r.unit()).isNull();
        assertThat(r.unitStated()).isFalse();
    }

    @Test
    void candidateNormalizerLeavesRateUnitEmpty() {
        FactCandidate c = new FactCandidate("tariff", ValueKind.RATE).valueText("4.50");
        CandidateNormalizer.normalize(c);
        assertThat(c.unit()).isNull();
        assertThat(c.valueNumber()).isEqualTo(4.50);
    }

    @Test
    void candidateNormalizerSetsMoneySlots() {
        FactCandidate c = new FactCandidate("gross_total", ValueKind.MONEY)
                .valueText("Rs. 12.5 lakhs");
        CandidateNormalizer.normalize(c);
        assertThat(c.scale()).isEqualTo("lakh");
        assertThat(c.currency()).isEqualTo("INR");
        assertThat(c.absoluteAmount()).isEqualTo("1250000");
    }
}
