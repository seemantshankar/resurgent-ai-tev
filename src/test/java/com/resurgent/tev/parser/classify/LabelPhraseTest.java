package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LabelPhraseTest {

    @Test
    void spellingVariantsShareOneKey() {
        String a = LabelPhrase.canonical("Fire Fighting Work");
        assertThat(LabelPhrase.canonical("FIRE-FIGHTING WORK ")).isEqualTo("fire fighting work").isEqualTo(a);
        assertThat(LabelPhrase.canonical("2. Fire fighting works (2.5% of civil cost)")).isEqualTo(a);
        assertThat(LabelPhrase.canonical("  fire\nfighting  work")).isEqualTo(a);
    }

    @Test
    void ampersandAndPluralsFold() {
        assertThat(LabelPhrase.canonical("Furniture & Accessories"))
                .isEqualTo(LabelPhrase.canonical("Furniture and Accessory"));
        assertThat(LabelPhrase.canonical("Liabilities")).isEqualTo("liability");
    }

    @Test
    void numbersAndRatesAreDropped() {
        assertThat(LabelPhrase.canonical("Less: Depreciation @ 13.91%")).isEqualTo("less depreciation");
        assertThat(LabelPhrase.canonical("28.0")).isEmpty();
        assertThat(LabelPhrase.canonical("2022-23")).isEmpty();
    }

    @Test
    void percentSignStaysMeaningful() {
        assertThat(LabelPhrase.canonical("% of Cash Profit")).isEqualTo("percent of cash profit");
    }

    @Test
    void unbalancedParenthesesAreTrimmed() {
        assertThat(LabelPhrase.canonical("Term Loans (Excld. Instalments")).isEqualTo("term loan");
    }

    @Test
    void genericAndStructuralLabelsAreNotLearnable() {
        for (String label : new String[] {"Total", "Sub Total", "Grand Total", "A", "b)", "Particulars",
                "Others", "S. No.", "28.0", "", "Misc."}) {
            assertThat(LabelPhrase.isLearnable(LabelPhrase.canonical(label))).as(label).isFalse();
        }
    }

    @Test
    void specificLabelsAreLearnable() {
        assertThat(LabelPhrase.isLearnable(LabelPhrase.canonical("Firefighting & Misc."))).isTrue();
        assertThat(LabelPhrase.isLearnable(LabelPhrase.canonical("Plumbing Works"))).isTrue();
        assertThat(LabelPhrase.isLearnable(LabelPhrase.canonical("PBDIT"))).isTrue();
    }

    @Test
    void overlongPhrasesAreNotLearnable() {
        assertThat(LabelPhrase.isLearnable(LabelPhrase.canonical("word ".repeat(30)))).isFalse();
        assertThat(LabelPhrase.isLearnable(LabelPhrase.canonical("a b c d e f g h i j"))).isFalse();
    }
}
