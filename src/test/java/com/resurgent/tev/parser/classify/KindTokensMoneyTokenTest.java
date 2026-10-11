package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** MONEY_TOKEN matches money words as words, not as fragments of other words. */
class KindTokensMoneyTokenTest {

    private static boolean money(String label) {
        return KindTokens.MONEY_TOKEN.matcher(label).find();
    }

    @Test
    void wordsInsideOtherWordsAreNotMoney() {
        for (String label : new String[] {
                "Coffee Table", "Coffee Shop cum Bakery", "Less Excise Duty (net of refund)",
                "PROFITABILITY ASSUMPTIONS", "Distribution Feeder Pillar", "Fire discharge line",
                "Units placed in service"}) {
            assertThat(money(label)).as(label).isFalse();
        }
    }

    @Test
    void moneyWordsStillMatchSingularPluralAndInSentences() {
        for (String label : new String[] {
                "Sundry Creditors", "Current liability", "Accumulated losses *", "Other Income",
                "Total cost", "Project Cost", "Less: Depreciation @ 10 %", "Less :", "TOTAL DEP. OF THE YEAR",
                "Amount (Rs.)", "Yearly Sales :Lakhs Rs.", "Rs. In Lacs", "Fee (₹)", "Bills discounted by bankers",
                "Consultancy & Project management fees", "Interest on Term Loan", "MARGIN MONEY / INVSTT.",
                "TOTAL ROOM SALES(Rs. In Lacs)", "Net Present Value (Rs.)", "$ 100", "CASH CREDIT:"}) {
            assertThat(money(label)).as(label).isTrue();
        }
    }
}
