package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What the static cue words may decide about a label on their own. They classify a label only when the
 * words say so confidently; a word that can name an amount or a rate (interest, margin, discount) is
 * left to the formula and the models.
 */
class StaticKindTest {

    private static String kind(String label) {
        return CellTypeClassifierLlm.staticKind(KindTokens.normalizeLabel(label));
    }

    @Test
    void anExplicitPercentSignOrWordIsPercentWhateverMoneyWordSitsBesideIt() {
        for (String label : new String[] {
                "Interest rate (%)", "Profit margin (%)", "% of Cash Profit", "Discount (%)",
                "INCREASE/DECREASE IN NET SALES (%)", "Tax rate (%)"}) {
            assertThat(kind(label)).as(label).isEqualTo(ReadingOutcome.PERCENT);
        }
    }

    /** A percentage that says how an amount is worked out is a parameter of a money row, not its unit. */
    @Test
    void aPercentageThatParameterisesAnAmountDoesNotMakeItAPercent() {
        for (String label : new String[] {
                "Fire Fighting Work (2.5% of civil cost)", "Less: Depreciation @ 10 %", "Repairs @ 0.75% of assets cost"}) {
            assertThat(kind(label)).as(label).isEqualTo(ReadingOutcome.MONEY);
        }
    }

    @Test
    void aWordThatNamesAnAmountOrARateDecidesNothingByItself() {
        for (String label : new String[] {
                "Interest rate", "Interest coverage ratio", "Gross margin", "Profit margin", "Discount allowed",
                "Dividend payout ratio", "Tariff per unit", "Tax rate", "Service charge rate", "Return on equity"}) {
            assertThat(kind(label)).as(label).isNull();
        }
    }

    @Test
    void anAmbiguousWordBesideAnUnambiguousMoneyWordIsMoney() {
        for (String label : new String[] {"Interest on Term Loan", "Interest on Working Capital", "Interest on loan"}) {
            assertThat(kind(label)).as(label).isEqualTo(ReadingOutcome.MONEY);
        }
    }

    @Test
    void generalMoneyTermsAreMoney() {
        for (String label : new String[] {
                "Salary", "Salaries & wages", "Wages", "Rent", "Rental income", "Remuneration to directors",
                "Bonus", "Commission", "Royalty", "Insurance", "Provision for taxation", "Depreciation",
                "Total cost", "Revenue", "Excise duty"}) {
            assertThat(kind(label)).as(label).isEqualTo(ReadingOutcome.MONEY);
        }
    }

    @Test
    void moneyWordsInsideOtherWordsAreNotMoney() {
        for (String label : new String[] {"Current ratio of staff", "Parent company", "Warranty period", "Apparent"}) {
            assertThat(kind(label)).as(label).isNull();
        }
    }

    @Test
    void aPeriodIsNotARateAndCountsStayCounts() {
        assertThat(kind("Revenue per annum")).isEqualTo(ReadingOutcome.MONEY);
        assertThat(kind("No. of rooms")).isEqualTo(ReadingOutcome.QUANTITY);
        assertThat(kind("Salary per month")).isEqualTo(ReadingOutcome.MONEY);
        assertThat(kind("Cost per sqm")).isNull();
    }
}
