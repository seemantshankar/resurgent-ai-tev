package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Deterministic money normalization with the document's money context — the Excel
 * treatment: figures stay numbers, currency and scale come from what the document
 * states, and unstated slots are only filled when the context resolves one answer.
 */
class CandidateNormalizerTest {

    @Test
    void contextFillsMissingCurrencyAndScale() {
        FactCandidate c = new FactCandidate("net_sales", ValueKind.MONEY)
                .valueText("12.5")
                .valueNumber(12.5);
        CandidateNormalizer.normalize(c,
                new DocMoneyContext("INR", MoneyNormalizer.Scale.LAKH));
        assertThat(c.currency()).isEqualTo("INR");
        assertThat(c.scale()).isEqualTo("lakh");
        assertThat(c.absoluteAmount()).isEqualTo("1250000");
    }

    @Test
    void scaleStatedInTextWinsOverContext() {
        FactCandidate c = new FactCandidate("revenue", ValueKind.MONEY)
                .valueText("Rs. 12.5 lakhs");
        CandidateNormalizer.normalize(c,
                new DocMoneyContext("INR", MoneyNormalizer.Scale.CRORE));
        assertThat(c.scale()).isEqualTo("lakh");
        assertThat(c.currency()).isEqualTo("INR");
        assertThat(c.absoluteAmount()).isEqualTo("1250000");
    }

    @Test
    void vendorMetaCurrencyWinsOverContext() {
        FactCandidate c = new FactCandidate("revenue", ValueKind.MONEY)
                .valueText("1200")
                .valueNumber(1200.0)
                .currency("USD");
        CandidateNormalizer.normalize(c, new DocMoneyContext("INR", null));
        assertThat(c.currency()).isEqualTo("USD");
    }

    @Test
    void numericValueFallsBackToTheFigureWhenTextIsNotAnAmount() {
        // line items keep their description as value_text; the amount rides in
        // value_number, so money slots are derived from the figure itself
        FactCandidate c = new FactCandidate("line_items", ValueKind.MONEY)
                .valueText("Loan sanction fees")
                .valueNumber(5000000.0);
        CandidateNormalizer.normalize(c, new DocMoneyContext("INR", null));
        assertThat(c.valueText()).isEqualTo("Loan sanction fees");
        assertThat(c.currency()).isEqualTo("INR");
        // context states no scale: like Excel V36 the figure keeps no scale
        // and no absolute amount
        assertThat(c.scale()).isNull();
        assertThat(c.absoluteAmount()).isNull();
    }

    @Test
    void scientificNotationTextIsRepairedFromTheNumber() {
        FactCandidate c = new FactCandidate("net_worth", ValueKind.MONEY)
                .valueText("5.7117825243E8")
                .valueNumber(5.7117825243E8);
        CandidateNormalizer.normalize(c, DocMoneyContext.EMPTY);
        assertThat(c.valueText()).isEqualTo("571178252.43");
        assertThat(c.scale()).isNull();
        assertThat(c.absoluteAmount()).isNull();
    }

    @Test
    void statedUnitContextLabelsBareFigures() {
        // the ATC shape: "(Amount in Rs.)" on the page states UNIT scale, so
        // bare figures get a scale and their face value as absolute amount
        FactCandidate c = new FactCandidate("net_sales", ValueKind.MONEY)
                .valueText("571178252.43")
                .valueNumber(5.7117825243E8);
        CandidateNormalizer.normalize(c,
                new DocMoneyContext("INR", MoneyNormalizer.Scale.UNIT));
        assertThat(c.currency()).isEqualTo("INR");
        assertThat(c.scale()).isEqualTo("unit");
        assertThat(c.absoluteAmount()).isEqualTo("571178252.43");
    }

    @Test
    void unstatedFigureWithoutContextKeepsNoScaleNoAbsolute() {
        FactCandidate c = new FactCandidate("revenue", ValueKind.MONEY)
                .valueText("18000")
                .valueNumber(18000.0);
        CandidateNormalizer.normalize(c, DocMoneyContext.EMPTY);
        assertThat(c.currency()).isNull();
        assertThat(c.scale()).isNull();
        assertThat(c.absoluteAmount()).isNull();
        assertThat(c.valueNumber()).isEqualTo(18000.0);
    }

    @Test
    void plainNormalizeStillWorks() {
        FactCandidate c = new FactCandidate("nw", ValueKind.MONEY).valueText("₹ 2.45 crores");
        CandidateNormalizer.normalize(c);
        assertThat(c.currency()).isEqualTo("INR");
        assertThat(c.scale()).isEqualTo("crore");
        assertThat(c.absoluteAmount()).isEqualTo("24500000");
    }

    // --- non-money kinds: mirror the Excel cell_reading treatment ---

    @Test
    void percentFactStoredAsFractionLikeExcel() {
        // Excel divides written percents by 100 at ingest and carries no unit
        // ("12.5%" → 0.125, unit ''); the display text keeps what the page said.
        FactCandidate c = new FactCandidate("iou_rate", ValueKind.PERCENT)
                .valueText("12.5%");
        CandidateNormalizer.normalize(c);
        assertThat(c.valueNumber()).isEqualTo(0.125);
        assertThat(c.unit()).isEmpty();
        assertThat(c.scale()).isEqualTo("unit");
        assertThat(c.valueText()).isEqualTo("12.5%");
    }

    @Test
    void perAnnumPercentKeepsItsQualifier() {
        FactCandidate c = new FactCandidate("interest", ValueKind.PERCENT)
                .valueText("4.5% p.a.");
        CandidateNormalizer.normalize(c);
        assertThat(c.valueNumber()).isEqualTo(0.045);
        assertThat(c.unit()).isEqualTo("percent_pa");
        assertThat(c.scale()).isEqualTo("unit");
    }

    @Test
    void rateKeepsItsStatedUnitAndUnitScale() {
        FactCandidate c = new FactCandidate("escrow_yield", ValueKind.RATE)
                .valueText("4.5/month");
        CandidateNormalizer.normalize(c);
        assertThat(c.valueNumber()).isEqualTo(4.5);
        assertThat(c.unit()).isEqualTo("month");
        assertThat(c.scale()).isEqualTo("unit");
    }

    @Test
    void quantityParsesFigureAndUnit() {
        FactCandidate c = new FactCandidate("land_area", ValueKind.QUANTITY)
                .valueText("4.5 acres");
        CandidateNormalizer.normalize(c);
        assertThat(c.valueNumber()).isEqualTo(4.5);
        assertThat(c.unit()).isEqualTo("acre");
        assertThat(c.scale()).isEqualTo("unit");
    }

    @Test
    void quantityUsesTheExcelUnitAliasRules() {
        // rooms → room is the parser side's canonical spelling (KindTokens);
        // document quantities must key the same way or joins drift
        FactCandidate c = new FactCandidate("guest_rooms", ValueKind.QUANTITY)
                .valueText("200 rooms");
        CandidateNormalizer.normalize(c);
        assertThat(c.valueNumber()).isEqualTo(200.0);
        assertThat(c.unit()).isEqualTo("room");
    }

    @Test
    void quantityWithoutUnitStatesNone() {
        FactCandidate c = new FactCandidate("capacity", ValueKind.QUANTITY)
                .valueText("1200");
        CandidateNormalizer.normalize(c);
        assertThat(c.valueNumber()).isEqualTo(1200.0);
        assertThat(c.unit()).isNull();
        assertThat(c.scale()).isEqualTo("unit");
    }

    @Test
    void quantityMetaUnitIsCanonicalized() {
        // schema-declared units arrive mixed case ("MWp"); storage keeps the
        // canonical key so Excel-side and document-side groupings agree
        FactCandidate c = new FactCandidate("capacity", ValueKind.QUANTITY)
                .valueText("100")
                .unit("MWp");
        CandidateNormalizer.normalize(c);
        assertThat(c.unit()).isEqualTo("mwp");
    }

    @Test
    void scientificNotationIsNotMistakenForAUnit() {
        FactCandidate c = new FactCandidate("capacity", ValueKind.QUANTITY)
                .valueText("5.71E8");
        CandidateNormalizer.normalize(c);
        assertThat(c.valueNumber()).isEqualTo(571000000.0);
        assertThat(c.unit()).isNull();
    }

    @Test
    void proseQuantityIsLeftAsStated() {
        FactCandidate c = new FactCandidate("plant_machinery", ValueKind.QUANTITY)
                .valueText("as per annexure B");
        CandidateNormalizer.normalize(c);
        assertThat(c.valueNumber()).isNull();
        assertThat(c.unit()).isNull();
        assertThat(c.scale()).isEqualTo("unit");
    }
}
