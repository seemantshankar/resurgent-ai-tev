package com.resurgent.tev.doc.compare;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The verdict set later M3 stages persist must stay in one closed vocabulary. */
class VerdictTest {

    @Test
    void everyVerdictHasAStableWireName() {
        assertThat(Verdict.AGREE.wireName()).isEqualTo("agree");
        assertThat(Verdict.DISAGREE.wireName()).isEqualTo("disagree");
        assertThat(Verdict.DIFFERENT_READINGS.wireName()).isEqualTo("different_readings");
        assertThat(Verdict.PRECISION_MISMATCH.wireName()).isEqualTo("precision_mismatch");
        assertThat(Verdict.NOT_COMPARABLE.wireName()).isEqualTo("not_comparable");
    }
}
