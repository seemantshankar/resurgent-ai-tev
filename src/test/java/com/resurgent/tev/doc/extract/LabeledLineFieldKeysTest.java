package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LabeledLineFieldKeysTest {

    @Test
    void acronymsAndLabelsBecomeStableKeys() {
        assertThat(LabeledLineFieldKeys.fromLabel("TAN")).isEqualTo("tan");
        assertThat(LabeledLineFieldKeys.fromLabel("Tax Deduction Account Number (TAN)"))
                .isEqualTo("tan");
        assertThat(LabeledLineFieldKeys.fromLabel("Registered Office")).isEqualTo("registered_office");
    }
}
