package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.nomenclature.IndustryResolution;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayerBPromptAssemblerTest {

    @Test
    void userMessageSendsAmountsWithNearbyContextNotDistantNoise() {
        Packet fat = new Packet(1L, 1L, 1L, "child", List.of(
                new PacketCell(1L, 1L, "A1", 1, 1, PacketCell.ROLE_CONTEXT, "string",
                        "Project Cost", "Project Cost", null, null, false, false),
                new PacketCell(2L, 1L, "B1", 1, 2, PacketCell.ROLE_CONTEXT, "string",
                        "Amount", "Amount", null, null, false, false),
                new PacketCell(3L, 1L, "A12", 12, 1, PacketCell.ROLE_CORE, "string",
                        "Civil Works", "Civil Works", null, null, false, false),
                new PacketCell(4L, 1L, "B12", 12, 2, PacketCell.ROLE_CORE, "number",
                        null, "100", "100", null, false, false),
                new PacketCell(5L, 1L, "Z99", 99, 26, PacketCell.ROLE_CONTEXT, "string",
                        "noise-far", "noise-far", null, null, false, false)),
                List.of(), true);
        OntologySlice slice = new OntologySlice(
                IndustryResolution.confirmed("hotel"),
                List.of(new NomenclatureNode(
                        "Project Cost > Civil Works", "Civil Works", "Project Cost",
                        NomenclatureNode.LAYER_SPINE, false, false, null, null)),
                List.of());
        LayerBPrompt prompt = new LayerBPrompt(
                fat, slice,
                new LayerAJudgment(ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                        List.of(), List.of(), null),
                null);

        String user = LayerBPromptAssembler.userMessage(prompt);
        assertThat(user).contains("\"amounts\"");
        assertThat(user).contains("\"context\"");
        assertThat(user).contains("Civil Works");
        assertThat(user).contains("Project Cost");
        assertThat(user).contains("Amount");
        assertThat(user).contains("B12");
        assertThat(user).contains("\"colHeader\"");
        assertThat(user).doesNotContain("noise-far");
        assertThat(user).doesNotContain("\"cells\"");
        assertThat(LayerBPromptAssembler.index(prompt).amounts()).hasSize(1);
        assertThat(LayerBPromptAssembler.index(prompt).amounts().get(0).columnHeader())
                .isEqualTo("Amount");
    }
}
