package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.discover.PacketRangeRef;
import com.resurgent.tev.parser.nomenclature.IndustryResolution;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayerAPromptAssemblerTest {

    private static final OntologySlice SLICE = new OntologySlice(
            IndustryResolution.confirmed("hotel"),
            List.of(new NomenclatureNode(
                    "Project Cost", "Project Cost", null,
                    NomenclatureNode.LAYER_SPINE, true, false, null, null)),
            List.of());

    @Test
    void cheapPassPacketIsStructuralOnlyOmittingFormulaBodiesAndNumericPayload() {
        Packet fat = new Packet(1L, 1L, 1L, "coverage_parent", List.of(
                new PacketCell(1L, 1L, "A1", 1, 1, PacketCell.ROLE_CONTEXT, "string",
                        "CAPITAL COST", "CAPITAL COST", null, null, false, false),
                new PacketCell(2L, 1L, "B2", 2, 2, PacketCell.ROLE_CORE, "number",
                        null, "999", "999", null, false, false),
                new PacketCell(3L, 1L, "A3", 3, 1, PacketCell.ROLE_CORE, "string",
                        "Civil Works", "Civil Works", null, null, false, false),
                new PacketCell(4L, 1L, "B3", 3, 2, PacketCell.ROLE_CORE, "number",
                        null, "100", "100",
                        "SUM(Sheet1!A1:Z500)+VLOOKUP(A3,Huge!A:B,2,FALSE)+"
                                + "SUMIF(Other!A:A,A3,Other!B:B)+INDEX(Match!C:C,MATCH(A3,Match!A:A,0))",
                        false, false)),
                List.of(new PacketRangeRef(4L, 99L, "Huge!A1:Z500", 1L, 12_000)),
                true);

        String compact = LayerAPromptAssembler.userMessage(
                new LayerAPrompt(fat, SLICE, null, true));
        String full = LayerAPromptAssembler.userMessage(
                new LayerAPrompt(fat, SLICE, null, false));

        assertThat(compact).contains("CAPITAL COST");
        assertThat(compact).contains("Civil Works");
        assertThat(compact).contains("Huge!A1:Z500");
        assertThat(compact).contains("\"cheapPass\":true");
        assertThat(compact).doesNotContain("VLOOKUP");
        assertThat(compact).doesNotContain("\"numeric\"");
        assertThat(compact).doesNotContain("\"formula\"");
        assertThat(compact.length()).isLessThan(full.length());
        assertThat(full).doesNotContain("VLOOKUP");
        assertThat(full).contains("\"hasFormula\":true");
        assertThat(full).contains("\"formulaChars\"");
    }

    @Test
    void systemPromptInstructsBrevityAndJsonOnly() {
        assertThat(LayerAPromptAssembler.SYSTEM).contains("JSON object only");
        assertThat(LayerAPromptAssembler.SYSTEM).contains("no markdown");
        assertThat(LayerAPromptAssembler.SYSTEM).contains("no internal deliberation");
    }
}
