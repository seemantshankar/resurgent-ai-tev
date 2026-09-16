package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.nomenclature.IndustryResolution;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayerBJobSplitterTest {

    @Test
    void doesNotSplitWhenAmountsFitInOneChunk() {
        LayerBPrompt prompt = promptWithAmounts(10);
        List<LayerBPrompt> chunks = LayerBJobSplitter.chunks(prompt);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isSameAs(prompt);
        assertThat(LayerBAmountSupport.amountCells(chunks.get(0).packet())).hasSize(10);
    }

    @Test
    void splitsLargeAmountSetsIntoBoundedChunksPreservingContext() {
        int total = LayerBJobSplitter.MAX_AMOUNTS_PER_CHUNK + 15;
        LayerBPrompt prompt = promptWithAmounts(total);
        List<LayerBPrompt> chunks = LayerBJobSplitter.chunks(prompt);

        assertThat(chunks).hasSize(2);
        assertThat(LayerBAmountSupport.amountCells(chunks.get(0).packet()))
                .hasSize(LayerBJobSplitter.MAX_AMOUNTS_PER_CHUNK);
        assertThat(LayerBAmountSupport.amountCells(chunks.get(1).packet())).hasSize(15);
        for (LayerBPrompt chunk : chunks) {
            assertThat(chunk.packet().cells())
                    .anyMatch(c -> "Section".equals(c.textValue()));
            assertThat(LayerBAmountSupport.amountCells(chunk.packet()).size())
                    .isLessThanOrEqualTo(LayerBJobSplitter.MAX_AMOUNTS_PER_CHUNK);
        }
        long distinctCoords = chunks.stream()
                .flatMap(c -> LayerBAmountSupport.amountCells(c.packet()).stream())
                .map(PacketCell::coord)
                .distinct()
                .count();
        assertThat(distinctCoords).isEqualTo(total);
    }

    private static LayerBPrompt promptWithAmounts(int amountCount) {
        List<PacketCell> cells = new ArrayList<>();
        cells.add(new PacketCell(1L, 1L, "A1", 1, 1, PacketCell.ROLE_CONTEXT, "string",
                "Section", "Section", null, null, false, false));
        for (int i = 0; i < amountCount; i++) {
            int row = i + 2;
            cells.add(new PacketCell(
                    100L + i, 1L, "A" + row, row, 1, PacketCell.ROLE_CORE, "string",
                    "Line " + i, "Line " + i, null, null, false, false));
            cells.add(new PacketCell(
                    200L + i, 1L, "B" + row, row, 2, PacketCell.ROLE_CORE, "number",
                    null, String.valueOf(i), String.valueOf(i), null, false, false));
        }
        Packet packet = new Packet(9L, 1L, 1L, "child", cells, List.of(), true);
        OntologySlice slice = new OntologySlice(
                IndustryResolution.confirmed("hotel"),
                List.of(new NomenclatureNode(
                        "Project Cost > Civil Works", "Civil Works", "Project Cost",
                        NomenclatureNode.LAYER_SPINE, false, false, null, null)),
                List.of());
        return new LayerBPrompt(
                packet,
                slice,
                new LayerAJudgment(
                        ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                        List.of(), List.of(), null),
                null);
    }
}
