package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.nomenclature.IndustryResolution;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit: {@link LabelGapFiller}. Two constraints a dropped attempt found the hard
 * way — number-redact every synthesised packet, and never mix Candidates into one —
 * plus the property the old chunk splitter lacked: a label lands in exactly one
 * batch, so one label gets exactly one answer.
 */
class LabelGapFillerTest {

    private static final OntologySlice SLICE =
            new OntologySlice(IndustryResolution.confirmed("hotel"), List.of(), List.of());
    private static final LayerAJudgment LAYER_A = new LayerAJudgment(
            ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
            List.of(), List.of(), null);

    private static PacketCell amount(long cellId, String coord, String value) {
        return new PacketCell(
                cellId, 9L, coord, 2, 2, PacketCell.ROLE_CORE, "number",
                null, value, value, null, false, false);
    }

    private static LabelGapFiller.Queued queued(String label, long candidateId, String coord) {
        PacketCell cell = amount(candidateId * 100, coord, "10");
        return new LabelGapFiller.Queued(
                new QualifiedLabel("Project Cost", label),
                cell,
                LabelGapFiller.labelCellFor(cell, label),
                candidateId);
    }

    @Test
    void everySynthesisedPacketIsNumberRedactedAtSendTime() {
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm();
        PacketCell live = amount(1L, "B2", "9998887");

        new LabelGapFiller(llm).fill(
                List.of(new LabelGapFiller.Queued(
                        new QualifiedLabel("Project Cost", "Civil Works"),
                        live,
                        LabelGapFiller.labelCellFor(live, "Civil Works"),
                        40L)),
                SLICE,
                LAYER_A);

        assertThat(llm.layerBPrompts).hasSize(1);
        assertThat(llm.layerBPrompts.get(0).packet().cells())
                .as("gap fill builds its own payload, so it must redact it itself")
                .allSatisfy(cell -> {
                    assertThat(nullToEmpty(cell.numericValue())).doesNotContain("9998887");
                    assertThat(nullToEmpty(cell.displayValue())).doesNotContain("9998887");
                });
    }

    @Test
    void aPacketIsNeverMixedAcrossCandidates() {
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm();

        new LabelGapFiller(llm).fill(
                List.of(queued("Civil Works", 40L, "B2"), queued("Plant", 41L, "C3")),
                SLICE,
                LAYER_A);

        assertThat(llm.layerBPrompts).hasSize(2);
        assertThat(llm.layerBPrompts)
                .extracting(prompt -> prompt.packet().candidateId())
                .containsExactlyInAnyOrder(40L, 41L);
    }

    @Test
    void oneLabelIsAskedOnceHoweverManyCellsCarryIt() {
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm();
        llm.layerBFactory = prompt -> prompt.packet().cells().stream()
                .filter(cell -> "number".equals(cell.valueType()))
                .map(cell -> new LayerBLineJudgment(
                        cell.coord(), "Civil Works",
                        "Project Cost > Civil Works > Structure",
                        AmountRole.ADD, List.of(), 0.9))
                .toList();

        List<LabelGapFiller.Queued> queued = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PacketCell cell = amount(i + 1, "B" + (i + 2), "10");
            queued.add(new LabelGapFiller.Queued(
                    new QualifiedLabel("Project Cost", "Civil Works"),
                    cell,
                    LabelGapFiller.labelCellFor(cell, "Civil Works"),
                    40L));
        }

        var answers = new LabelGapFiller(llm).fill(queued, SLICE, LAYER_A);

        assertThat(llm.layerBPrompts)
                .as("five cells, one label, one question")
                .hasSize(1);
        assertThat(answers).hasSize(1);
        assertThat(answers).containsKey("project cost > civil works");
    }

    @Test
    void aLabelLandsInExactlyOneBatchHoweverManyLabelsThereAre() {
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm();
        List<LabelGapFiller.Queued> queued = new ArrayList<>();
        int labels = LabelGapFiller.BATCH_SIZE * 2 + 7;
        for (int i = 0; i < labels; i++) {
            queued.add(queued("Line " + i, 40L, "B" + (i + 2)));
        }

        new LabelGapFiller(llm).fill(queued, SLICE, LAYER_A);

        List<String> sentCoords = new ArrayList<>();
        for (LayerBPrompt prompt : llm.layerBPrompts) {
            prompt.packet().cells().stream()
                    .filter(cell -> "number".equals(cell.valueType()))
                    .map(PacketCell::coord)
                    .forEach(sentCoords::add);
        }
        assertThat(sentCoords).doesNotHaveDuplicates();
        assertThat(llm.layerBPrompts).hasSize(3);
    }

    @Test
    void aGroupTheModelCannotAnswerCostsThatGroupAndNotTheRun() {
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm();
        llm.layerBFactory = prompt -> {
            throw new IllegalStateException("provider unavailable");
        };

        LabelGapFiller filler = new LabelGapFiller(llm);
        var answers = filler.fill(
                List.of(queued("Civil Works", 40L, "B2")), SLICE, LAYER_A);

        assertThat(answers).isEmpty();
        assertThat(filler.failures()).singleElement()
                .asString().contains("provider unavailable");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
