package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.nomenclature.IndustryResolution;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayerBResponseParserTest {

    private static LayerBPromptIndex index() {
        Packet packet = new Packet(1L, 1L, 1L, "child", List.of(
                new PacketCell(1L, 1L, "A12", 12, 1, PacketCell.ROLE_CORE, "string",
                        "Civil Works", "Civil Works", null, null, false, false),
                new PacketCell(2L, 1L, "B12", 12, 2, PacketCell.ROLE_CORE, "number",
                        null, "100", "100", null, false, false),
                new PacketCell(3L, 1L, "A31", 31, 1, PacketCell.ROLE_CORE, "string",
                        "Less : AC", "Less : AC", null, null, false, false),
                new PacketCell(4L, 1L, "F31", 31, 6, PacketCell.ROLE_CORE, "number",
                        null, "15", "15", null, false, false)),
                List.of(), true);
        OntologySlice slice = new OntologySlice(
                IndustryResolution.confirmed("hotel"),
                List.of(
                        new NomenclatureNode(
                                "Project Cost", "Project Cost", null,
                                NomenclatureNode.LAYER_SPINE, true, false, null, null),
                        new NomenclatureNode(
                                "Project Cost > Civil Works", "Civil Works", "Project Cost",
                                NomenclatureNode.LAYER_SPINE, false, false, null, null),
                        new NomenclatureNode(
                                "Project Cost > Civil Works > Structure", "Structure",
                                "Project Cost > Civil Works",
                                NomenclatureNode.LAYER_MANDATE_SOFT, false, true, null, 1L),
                        new NomenclatureNode(
                                "Project Cost > Plant & Machinery", "Plant & Machinery",
                                "Project Cost",
                                NomenclatureNode.LAYER_SPINE, false, false, null, null),
                        new NomenclatureNode(
                                "Project Cost > Plant & Machinery > Air Conditioning",
                                "Air Conditioning",
                                "Project Cost > Plant & Machinery",
                                NomenclatureNode.LAYER_INDUSTRY, false, true, "hotel", null)),
                List.of());
        LayerAJudgment layerA = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                List.of(), List.of(), null);
        return LayerBPromptAssembler.index(new LayerBPrompt(packet, slice, layerA, null));
    }

    @Test
    void resolvesCompactIndexTriples() {
        LayerBPromptIndex index = index();
        List<LayerBLineJudgment> lines = LayerBResponseParser.parse("""
                {"lines":[[0,2,0],[1,4,1]],"soft":[]}
                """, index);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).coord()).isEqualTo("B12");
        assertThat(lines.get(0).verbatim()).isEqualTo("Civil Works");
        assertThat(lines.get(0).path()).isEqualTo("Project Cost > Civil Works > Structure");
        assertThat(lines.get(0).amountRole()).isEqualTo(AmountRole.ADD);
        assertThat(lines.get(1).coord()).isEqualTo("F31");
        assertThat(lines.get(1).amountRole()).isEqualTo(AmountRole.DEDUCT);
        assertThat(lines.get(1).path())
                .isEqualTo("Project Cost > Plant & Machinery > Air Conditioning");
    }

    @Test
    void resolvesSoftLeafProposals() {
        LayerBPromptIndex index = index();
        List<LayerBLineJudgment> lines = LayerBResponseParser.parse("""
                {"lines":[],"soft":[{"c":0,"pp":1,"n":"Finishes","a":["Finish"],"r":0}]}
                """, index);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).path()).isEqualTo("Project Cost > Civil Works > Finishes");
        assertThat(lines.get(0).aliases()).containsExactly("Finish");
        assertThat(lines.get(0).verbatim()).isEqualTo("Civil Works");
    }

    @Test
    void rejectsOutOfRangeCellIndex() {
        assertThatThrownBy(() -> LayerBResponseParser.parse(
                "{\"lines\":[[9,0,0]],\"soft\":[]}", index()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cellIndex");
    }

    @Test
    void rejectsMissingLines() {
        assertThatThrownBy(() -> LayerBResponseParser.parse("{\"ok\":true}", index()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lines");
    }
}
