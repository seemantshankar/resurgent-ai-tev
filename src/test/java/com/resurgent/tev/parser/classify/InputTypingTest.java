package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InputTypingTest {

    @Test
    void percentNumberFormatTypesAnUnlabelledCellAsPercent() {
        GraphCell cell = new GraphCell(
                1L, 9L, "B2", 2, 2, null, true, false,
                null, "0.8", "0.8", "number", "0%");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.PERCENT);
    }

    @Test
    void aBareUnformattedNumberStaysUnlabelled() {
        GraphCell cell = new GraphCell(
                1L, 9L, "B2", 2, 2, null, true, false,
                null, "0.8", "0.8", "number", "General");

        assertThat(InputTyping.of(cell).isResolved()).isFalse();
    }

    @Test
    void aRateQuotedInTheRowLabelDoesNotTypeTheRowAsPercent() {
        GraphCell cell = new GraphCell(
                1L, 9L, "J33", 33, 10, null, true, false,
                "Less: Depreciation @ 10 %", "317.83", "317.83", "number", "General");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.MONEY);
    }

    @Test
    void aRateOnAnUnadornedMoneyRowLabelStillTypesMoney() {
        GraphCell cell = new GraphCell(
                1L, 9L, "J45", 45, 10, null, true, false,
                "Building @ 10 %", "9.50", "9.50", "number", "General");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.MONEY);
    }

    @Test
    void aPercentDisplayOnTheCellItselfStillTypesPercent() {
        GraphCell cell = new GraphCell(
                1L, 9L, "B45", 45, 2, null, true, false,
                "Building", "5.00%", "0.05", "number", "General");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.PERCENT);
    }

    @Test
    void aLabelThatNamesARateTypesPercent() {
        GraphCell cell = new GraphCell(
                1L, 9L, "B6", 6, 2, null, true, false,
                "BEP%", "37.85", "37.85", "number", "General");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.PERCENT);
    }

    @Test
    void aTotalDepreciationOfYearLabelTypesMoneyNotQuantity() {
        GraphCell cell = new GraphCell(
                1L, 9L, "J57", 57, 10, null, true, false,
                "TOTAL DEP. OF THE YEAR", "452.19", "452.19", "number", "0.00");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.MONEY);
    }
}
