package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.BindCellRow;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LayerBGridContextTest {

    private static BindCellRow cell(long id, String coord, int row, int col, String type, String text) {
        return new BindCellRow(id, coord, row, col, type, text, null, false, false, null);
    }

    @Test
    void anAmountToBindCarriesItsResolvedLabelsBecauseTheGridMayNotHoldThem() {
        List<BindCellRow> cells = List.of(
                cell(1, "B8", 8, 2, "text", "Basement"),
                cell(2, "C8", 8, 3, "number", null),
                cell(3, "C9", 9, 3, "number", null));

        String grid = LayerBPromptAssembler.grid(
                cells, Set.of("C8"), id -> id == 2 ? "row: Basement | column: Area in sq.m. | note: Exempted" : "");

        String[] lines = grid.split("\n");
        assertThat(lines[0]).doesNotContain("row:");                     // a text cell needs none
        assertThat(lines[1]).startsWith("bind\tC8\t8\t")
                .endsWith("\t[row: Basement | column: Area in sq.m. | note: Exempted]");
        assertThat(lines[2]).startsWith("kept\tC9").doesNotContain("[");  // already bound: nothing to add
    }

    @Test
    void withoutAContextSourceTheGridIsUnchanged() {
        List<BindCellRow> cells = List.of(cell(2, "C8", 8, 3, "number", null));

        assertThat(LayerBPromptAssembler.grid(cells, Set.of("C8")))
                .isEqualTo(LayerBPromptAssembler.grid(cells, Set.of("C8"), id -> ""));
    }

    @Test
    void thePromptTellsTheModelWhatTheBracketedContextIs() {
        assertThat(LayerBPromptAssembler.SYSTEM).contains("[row:").contains("resolved");
    }
}
