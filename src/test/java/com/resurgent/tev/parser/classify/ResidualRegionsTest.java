package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CellPacketView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResidualRegionsTest {

    private long nextId = 1;

    @Test
    void cellsNoRegionClaimedBecomeABlockWithTheirBoundingBox() {
        // a labelled 3x3 grid of amounts that the model's regions skipped
        List<CellPacketView> cells = new ArrayList<>();
        for (int r = 10; r <= 12; r++) {
            cells.add(text(r, 1, "item"));
            for (int c = 2; c <= 4; c++) {
                cells.add(number(r, c));
            }
        }

        List<ResidualRegions.Block> blocks = ResidualRegions.find(cells, Set.of());

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).minRow()).isEqualTo(10);
        assertThat(blocks.get(0).maxRow()).isEqualTo(12);
        assertThat(blocks.get(0).minCol()).isEqualTo(1);
        assertThat(blocks.get(0).maxCol()).isEqualTo(4);
        assertThat(blocks.get(0).memberIds()).hasSize(12);
    }

    @Test
    void cellsAnotherRegionOwnsAreLeftOut() {
        List<CellPacketView> cells = new ArrayList<>();
        for (int c = 1; c <= 5; c++) {
            cells.add(number(3, c));
        }
        Set<Long> owned = Set.of(cells.get(0).cellId(), cells.get(1).cellId());

        List<ResidualRegions.Block> blocks = ResidualRegions.find(cells, owned);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).memberIds()).doesNotContainAnyElementsOf(owned).hasSize(3);
    }

    @Test
    void aGapOfOneBlankStaysOneBlockButTwoSplitsThem() {
        List<CellPacketView> cells = new ArrayList<>();
        for (int c = 1; c <= 3; c++) {
            cells.add(number(5, c));          // block A: cols 1-3
        }
        for (int c = 5; c <= 7; c++) {
            cells.add(number(5, c));          // one blank column (4) away: same block
        }
        for (int c = 20; c <= 22; c++) {
            cells.add(number(5, c));          // far away: its own block
        }

        assertThat(ResidualRegions.find(cells, Set.of())).hasSize(2);
    }

    @Test
    void strayCellsAndLabelOnlyLeftoversAreNotWorthARegion() {
        List<CellPacketView> cells = List.of(number(2, 2), number(2, 3), text(40, 1, "note"), text(41, 1, "note"),
                text(42, 1, "note"));

        assertThat(ResidualRegions.find(cells, Set.of())).isEmpty();
    }

    private CellPacketView number(int row, int col) {
        return new CellPacketView(nextId++, 1L, "X" + row + "_" + col, row, col, "number", null, "5", "5", null, false, false);
    }

    private CellPacketView text(int row, int col, String text) {
        return new CellPacketView(nextId++, 1L, "X" + row + "_" + col, row, col, "text", text, text, null, null, false, false);
    }
}
