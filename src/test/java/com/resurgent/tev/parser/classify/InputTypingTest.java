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
}
