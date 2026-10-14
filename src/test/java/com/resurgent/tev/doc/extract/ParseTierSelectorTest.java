package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** LlamaParse tier-guide decision table (content-based tier selection). */
class ParseTierSelectorTest {

    private static ParseTierSelector.Signals signals(
            boolean ocr, int tables, int complexTables, int figures,
            boolean multiColumn, boolean dense, String fileRole, boolean critical) {
        return new ParseTierSelector.Signals(
                ocr, 5, tables, complexTables, figures, multiColumn, dense, fileRole, critical);
    }

    @Test
    void plainSingleColumnTextIsFast() {
        assertThat(ParseTierSelector.select(signals(
                false, 0, 0, 0, false, false, "other", false)))
                .isEqualTo(ParseTier.FAST);
    }

    @Test
    void denseMultiPageLegalTextIsCostEffective() {
        // AOA / MOA style: many pages of paragraphs, no tables
        assertThat(ParseTierSelector.select(new ParseTierSelector.Signals(
                false, 16, 0, 0, 0, false, true, "regulatory", false)))
                .isEqualTo(ParseTier.COST_EFFECTIVE);
    }

    @Test
    void shortPlainCertificateStaysFast() {
        assertThat(ParseTierSelector.select(new ParseTierSelector.Signals(
                false, 3, 0, 0, 0, false, false, "other", false)))
                .isEqualTo(ParseTier.FAST);
    }

    @Test
    void identityCardIsAgenticNotFast() {
        // ePAN / GST REG-06 style: seals, QR, multi-region — plain fast OCR mangles them
        assertThat(ParseTierSelector.select(new ParseTierSelector.Signals(
                false, 1, 0, 0, 0, false, false, "identity", false)))
                .isEqualTo(ParseTier.AGENTIC);
        assertThat(ParseTierSelector.select(new ParseTierSelector.Signals(
                false, 3, 0, 0, 0, false, false, "identity", false)))
                .isEqualTo(ParseTier.AGENTIC);
    }

    @Test
    void simpleTablesAreCostEffective() {
        assertThat(ParseTierSelector.select(signals(
                false, 1, 0, 0, false, false, "quotation", false)))
                .isEqualTo(ParseTier.COST_EFFECTIVE);
    }

    @Test
    void multiColumnLayoutIsAgentic() {
        assertThat(ParseTierSelector.select(signals(
                false, 0, 0, 0, true, false, "other", false)))
                .isEqualTo(ParseTier.AGENTIC);
    }

    @Test
    void scansAreAgentic() {
        assertThat(ParseTierSelector.select(signals(
                true, 0, 0, 0, false, false, "other", false)))
                .isEqualTo(ParseTier.AGENTIC);
    }

    @Test
    void figuresAreAgentic() {
        assertThat(ParseTierSelector.select(signals(
                false, 0, 0, 2, false, false, "other", false)))
                .isEqualTo(ParseTier.AGENTIC);
    }

    @Test
    void complexTablesAreAgenticPlus() {
        assertThat(ParseTierSelector.select(signals(
                false, 3, 2, 0, false, false, "other", false)))
                .isEqualTo(ParseTier.AGENTIC_PLUS);
    }

    @Test
    void denseFinancialWithTablesIsAgenticPlus() {
        assertThat(ParseTierSelector.select(signals(
                false, 2, 0, 0, false, true, "technical_report", false)))
                .isEqualTo(ParseTier.AGENTIC_PLUS);
    }

    @Test
    void missionCriticalForcesAgenticPlus() {
        assertThat(ParseTierSelector.select(signals(
                false, 1, 0, 0, true, false, "other", true)))
                .isEqualTo(ParseTier.AGENTIC_PLUS);
    }

    @Test
    void scanWithComplexTablesIsAgenticPlus() {
        assertThat(ParseTierSelector.select(signals(
                true, 2, 1, 0, false, false, "other", false)))
                .isEqualTo(ParseTier.AGENTIC_PLUS);
    }

    @Test
    void wireNamesMatchApi() {
        assertThat(ParseTier.FAST.wireName()).isEqualTo("fast");
        assertThat(ParseTier.COST_EFFECTIVE.wireName()).isEqualTo("cost_effective");
        assertThat(ParseTier.AGENTIC.wireName()).isEqualTo("agentic");
        assertThat(ParseTier.AGENTIC_PLUS.wireName()).isEqualTo("agentic_plus");
        assertThat(ParseTier.fromWire("agentic-plus")).isEqualTo(ParseTier.AGENTIC_PLUS);
    }

    @Test
    void convenienceOverloadMatches() {
        assertThat(ParseTierSelector.select(false, 0, false, 0)).isEqualTo(ParseTier.FAST);
        assertThat(ParseTierSelector.select(false, 1, false, 0))
                .isEqualTo(ParseTier.COST_EFFECTIVE);
        assertThat(ParseTierSelector.select(true, 0, false, 0)).isEqualTo(ParseTier.AGENTIC);
    }
}
