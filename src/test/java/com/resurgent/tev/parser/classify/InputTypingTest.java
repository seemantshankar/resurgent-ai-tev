package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InputTypingTest {

    @Test
    void percentNumberFormatTypesAnUnlabelledCellAsPercent() {
        GraphCell cell = new GraphCell(
                1L, 9L, "B2", 2, 2, null, true, false,
                null, null, "0.8", "0.8", "number", "0%");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.PERCENT);
    }

    @Test
    void aBareUnformattedNumberStaysUnlabelled() {
        GraphCell cell = new GraphCell(
                1L, 9L, "B2", 2, 2, null, true, false,
                null, null, "0.8", "0.8", "number", "General");

        assertThat(InputTyping.of(cell).isResolved()).isFalse();
    }

    @Test
    void aRateQuotedInTheRowLabelDoesNotTypeTheRowAsPercent() {
        GraphCell cell = new GraphCell(
                1L, 9L, "J33", 33, 10, null, true, false,
                "Less: Depreciation @ 10 %", null, "317.83", "317.83", "number", "General");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.MONEY);
    }

    @Test
    void aRateOnAnUnadornedMoneyRowLabelStillTypesMoney() {
        GraphCell cell = new GraphCell(
                1L, 9L, "J45", 45, 10, null, true, false,
                "Building @ 10 %", null, "9.50", "9.50", "number", "General");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.MONEY);
    }

    @Test
    void aPercentDisplayOnTheCellItselfStillTypesPercent() {
        GraphCell cell = new GraphCell(
                1L, 9L, "B45", 45, 2, null, true, false,
                "Building", null, "5.00%", "0.05", "number", "General");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.PERCENT);
    }

    @Test
    void aLabelThatNamesARateTypesPercent() {
        GraphCell cell = new GraphCell(
                1L, 9L, "B6", 6, 2, null, true, false,
                "BEP%", null, "37.85", "37.85", "number", "General");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.PERCENT);
    }

    @Test
    void aCommaFormattedDisplayValueDoesNotImplyThousandScale() {
        GraphCell cell = new GraphCell(
                1L, 9L, "B2", 2, 2, null, true, false,
                "Building", null, "1,000", "1,000", "number", "General");

        assertThat(InputTyping.scaleOf(cell)).isEqualTo(CellScale.UNIT);
    }

    @Test
    void aTotalDepreciationOfYearLabelTypesMoneyNotQuantity() {
        GraphCell cell = new GraphCell(
                1L, 9L, "J57", 57, 10, null, true, false,
                "TOTAL DEP. OF THE YEAR", null, "452.19", "452.19", "number", "0.00");

        ResolvedUnit unit = InputTyping.of(cell);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.MONEY);
    }

    @Test
    void anEntityRowTakesTheCurrencyUnitFromItsColumnHeader() {
        GraphCell tariff = new GraphCell(
                1L, 23L, "G15", 15, 7, null, true, false,
                "Deluxe Rooms", "AVERAGE TARIFF (in Rs.)",
                "5000.0", "5000.0", "number", "0.00");

        ResolvedUnit unit = InputTyping.of(tariff);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.MONEY);
    }

    @Test
    void anEntityRowTakesTheCountUnitFromItsColumnHeader() {
        GraphCell rooms = new GraphCell(
                1L, 23L, "D15", 15, 4, null, true, false,
                "Deluxe Rooms", "ROOMS FOR SALE", "190.0", "190.0", "number", "General");

        ResolvedUnit unit = InputTyping.of(rooms);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.QUANTITY);
    }

    @Test
    void aRowUnitOutranksTheColumnUnitWhenBothAreStated() {
        GraphCell explicitRow = new GraphCell(
                1L, 23L, "G46", 46, 7, null, true, false,
                "No. of Rooms", "AVERAGE TARIFF (in Rs.)",
                "5000.0", "5000.0", "number", "General");

        ResolvedUnit unit = InputTyping.of(explicitRow);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.QUANTITY);
    }

    @Test
    void aRowPercentOverrideBeatsAMonetaryColumnBanner() {
        GraphCell percent = new GraphCell(
                1L, 16L, "B33", 33, 2, null, true, false,
                "Envisaged Occupancy %", "Amount (Rs)", "0.4", "0.4", "number", "General");

        ResolvedUnit unit = InputTyping.of(percent);

        assertThat(unit.isResolved()).isTrue();
        assertThat(unit.kind()).isEqualTo(CellKind.PERCENT);
    }
}
