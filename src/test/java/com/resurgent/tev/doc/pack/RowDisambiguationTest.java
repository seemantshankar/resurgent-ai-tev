package com.resurgent.tev.doc.pack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RowDisambiguationTest {

    @Test
    void rejectsBareTotalHint() {
        assertThat(RowDisambiguation.isAmbiguousInstruction("Total", List.of("Total"))).isTrue();
        assertThat(RowDisambiguation.isAmbiguousInstruction(
                "Grand Total", List.of("Grand Total"))).isTrue();
    }

    @Test
    void acceptsDisambiguatedGrandTotal() {
        assertThat(RowDisambiguation.isAmbiguousInstruction(
                "Final grand total row including tax and freight (not section subtotals)",
                List.of("Grand Total"))).isFalse();
    }
}
