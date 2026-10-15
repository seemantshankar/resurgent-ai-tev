package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Document money context: the document-side mirror of the Excel pipeline's
 * {@code HomeCurrency} rule (one home currency for a run, unresolved when the
 * text names two) and stated-scale rule ("(Amount in Rs.)", "in lakhs").
 */
class DocMoneyContextTest {

    @Test
    void emptyWithoutPageText() {
        DocMoneyContext ctx = DocMoneyContext.from(List.of());
        assertThat(ctx.currency()).isNull();
        assertThat(ctx.scale()).isNull();
    }

    @Test
    void amountInRsHeaderNamesInrAndUnitScale() {
        DocMoneyContext ctx = DocMoneyContext.from(List.of(
                "Balance Sheet as at 31.03.2025",
                "(Amount in Rs.)\nNet Sales 571,178,252.43"));
        assertThat(ctx.currency()).isEqualTo("INR");
        assertThat(ctx.scale()).isEqualTo(MoneyNormalizer.Scale.UNIT);
    }

    @Test
    void figuresInLakhsStatementSetsLakhScale() {
        DocMoneyContext ctx = DocMoneyContext.from(List.of("Profit and Loss Account\n(all figures in lakhs)"));
        assertThat(ctx.scale()).isEqualTo(MoneyNormalizer.Scale.LAKH);
    }

    @Test
    void croresStatementSetsCroreScale() {
        DocMoneyContext ctx = DocMoneyContext.from(List.of("Balance Sheet (amount in crores)"));
        assertThat(ctx.scale()).isEqualTo(MoneyNormalizer.Scale.CRORE);
    }

    @Test
    void conflictingStatedScalesStayUnresolved() {
        DocMoneyContext ctx = DocMoneyContext.from(List.of("(in lakhs)", "(in crores)"));
        assertThat(ctx.scale()).isNull();
    }

    @Test
    void twoCurrenciesLeaveHomeCurrencyUnresolved() {
        // same rule as the Excel side: a bare number may only inherit one home currency
        DocMoneyContext ctx = DocMoneyContext.from(List.of("₹ 50,000", "USD 1,000"));
        assertThat(ctx.currency()).isNull();
    }

    @Test
    void indiaRegionTextNamesInr() {
        DocMoneyContext ctx = DocMoneyContext.from(List.of(
                "M/s ANAND TRADING COMPANY, Khandwa, Madhya Pradesh"));
        assertThat(ctx.currency()).isEqualTo("INR");
    }

    @Test
    void gstinAndPanNameIndia() {
        DocMoneyContext ctx = DocMoneyContext.from(List.of(
                "GSTIN: 23ACUPM9971C1Z5  PAN: ACUPM9971C"));
        assertThat(ctx.currency()).isEqualTo("INR");
    }

    @Test
    void plainTextDocumentWithoutCuesInventsNothing() {
        DocMoneyContext ctx = DocMoneyContext.from(List.of(
                "This agreement is entered into between the parties."));
        assertThat(ctx.currency()).isNull();
        assertThat(ctx.scale()).isNull();
    }
}
