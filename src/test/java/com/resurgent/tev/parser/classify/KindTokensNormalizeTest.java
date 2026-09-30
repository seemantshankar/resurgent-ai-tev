package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link KindTokens#normalizeLabel}: one spelling of a label for every matcher. */
class KindTokensNormalizeTest {

    @Test
    void nullAndBlankBecomeEmpty() {
        assertThat(KindTokens.normalizeLabel(null)).isEmpty();
        assertThat(KindTokens.normalizeLabel("   \n\t ")).isEmpty();
    }

    @Test
    void gluedParenthesisIsSplitSoCurrencyIsItsOwnWord() {
        String n = KindTokens.normalizeLabel("TOTAL ROOM SALES(Rs. In Lacs)");
        assertThat(n).isEqualTo("total room sales ( rs. in lacs )");
        assertThat(KindTokens.CURRENCY.matcher(n).find()).isTrue();
        assertThat(KindTokens.MONEY_TOKEN.matcher(n).find()).isTrue();
    }

    @Test
    void currencyGluedToScaleOrUnitWordIsSplit() {
        assertThat(KindTokens.normalizeLabel("Rs.crore")).isEqualTo("rs. crore");
        assertThat(KindTokens.normalizeLabel("Rs.sq.ft")).isEqualTo("rs. sq.ft");
        assertThat(KindTokens.normalizeLabel("Rs.Lakhs")).isEqualTo("rs. lakhs");
        assertThat(KindTokens.normalizeLabel("INRcrore")).isEqualTo("inr crore");
    }

    @Test
    void wordsThatMerelyContainRsAreNeverSplit() {
        assertThat(KindTokens.normalizeLabel("Others")).isEqualTo("others");
        assertThat(KindTokens.normalizeLabel("Banquet Chairs")).isEqualTo("banquet chairs");
        assertThat(KindTokens.normalizeLabel("Rsvp")).isEqualTo("rsvp");
    }

    @Test
    void newlinesNbspAndZeroWidthCharactersCollapseToSingleSpaces() {
        assertThat(KindTokens.normalizeLabel("Amt. \n(in Lac Rs.)")).isEqualTo("amt. ( in lac rs. )");
        assertThat(KindTokens.normalizeLabel("Net Sales​  Total")).isEqualTo("net sales total");
    }

    @Test
    void colonAtAndPercentGluedToWordsAreSplit() {
        assertThat(KindTokens.normalizeLabel("Add:Reserves")).isEqualTo("add : reserves");
        assertThat(KindTokens.normalizeLabel("BEP%")).isEqualTo("bep %");
        assertThat(KindTokens.normalizeLabel("Depreciation @10%")).isEqualTo("depreciation @10%");
    }

    @Test
    void lowerToUpperCamelIsSplitButAllCapsRunsAreNot() {
        assertThat(KindTokens.normalizeLabel("totalSales")).isEqualTo("total sales");
        assertThat(KindTokens.normalizeLabel("PBDIT")).isEqualTo("pbdit");
        assertThat(KindTokens.normalizeLabel("450 KVAR panel")).isEqualTo("450 kvar panel");
    }

    @Test
    void currencySymbolsSurvive() {
        assertThat(KindTokens.normalizeLabel("Fee (₹)")).isEqualTo("fee ( ₹ )");
        assertThat(KindTokens.normalizeLabel("Price $ / £")).isEqualTo("price $ / £");
    }

    @Test
    void veryLongLabelIsHandled() {
        String longLabel = "word ".repeat(2000);
        assertThat(KindTokens.normalizeLabel(longLabel)).startsWith("word word").hasSize(9999);
    }
}
