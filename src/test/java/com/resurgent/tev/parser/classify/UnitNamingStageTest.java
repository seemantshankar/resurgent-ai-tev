package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class UnitNamingStageTest {

    private static InterpretationCellView cell(long id, String coord, int row, int col) {
        return new InterpretationCellView(id, 1L, coord, row, col, "number", null, "6.31", "6.31", null, null,
                null, null, null, null, false, null, false, false, null, null);
    }

    private static CellContext context(Map<Long, String[]> labels) {
        return new CellContext() {
            @Override
            public String rowLabel(InterpretationCellView c) {
                return labels.get(c.cellId())[0];
            }

            @Override
            public String columnLabel(InterpretationCellView c) {
                return labels.get(c.cellId())[1];
            }

            @Override
            public RegionContext region(InterpretationCellView c) {
                return new RegionContext(7L, "generation", "Annual generation statement of a solar plant", "", "Generation", true);
            }

            @Override
            public String sheetName(InterpretationCellView c) {
                return "Generation";
            }

            @Override
            public String workbookKey() {
                return "test-workbook";
            }
        };
    }

    private static ClassifierLlm answering(Function<String, String> reply, List<String> prompts) {
        return new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of();
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                return null;
            }

            @Override
            public String classifyCellJson(String systemPrompt, String userPrompt, int maxTokens) {
                prompts.add(userPrompt);
                return reply.apply(userPrompt);
            }
        };
    }

    private static Map<Long, ReadingOutcome> typed(String unit, String source, long... ids) {
        Map<Long, ReadingOutcome> settled = new HashMap<>();
        for (long id : ids) {
            settled.put(id, ReadingOutcome.typed(ReadingOutcome.QUANTITY, CellScale.UNIT, unit, "", source));
        }
        return settled;
    }

    @Test
    void aUnitTheLabelsStateIsGivenToEveryCellUnderThatLabelAndMarkedStated() {
        List<InterpretationCellView> cells = List.of(cell(1, "C9", 9, 3), cell(2, "D9", 9, 4));
        Map<Long, String[]> labels = Map.of(
                1L, new String[] {"Gross Generation (MWh)", "Y1"}, 2L, new String[] {"Gross Generation (MWh)", "Y1"});
        Map<Long, ReadingOutcome> settled = typed("", ReadingOutcome.DERIVED, 1, 2);
        List<String> prompts = new ArrayList<>();
        var notes = UnitNamingStage.run(cells, settled, context(labels), answering(
                p -> "{\"groups\":[{\"id\":1,\"unit\":\"MWh\",\"basis\":\"stated\",\"evidence\":\"Gross Generation (MWh)\"}]}",
                prompts), null, 1);

        assertThat(prompts).hasSize(1); // one question for the label, not one per cell
        assertThat(settled.get(1L).unit).isEqualTo("MWh");
        assertThat(settled.get(2L).unit).isEqualTo("MWh");
        assertThat(notes.get(1L).basis()).isEqualTo(UnitNamingStage.STATED);
        assertThat(notes.get(2L).evidence()).isEqualTo("Gross Generation (MWh)");
    }

    @Test
    void aStatedUnitWhoseEvidenceIsNotInTheLabelsIsOnlyAnInference() {
        List<InterpretationCellView> cells = List.of(cell(1, "C9", 9, 3));
        Map<Long, String[]> labels = Map.of(1L, new String[] {"Gross Generation", "Y1"});
        Map<Long, ReadingOutcome> settled = typed("", ReadingOutcome.DERIVED, 1);
        var notes = UnitNamingStage.run(cells, settled, context(labels), answering(
                p -> "{\"groups\":[{\"id\":1,\"unit\":\"MWh\",\"basis\":\"stated\",\"evidence\":\"measured in megawatt hours\"}]}",
                new ArrayList<>()), null, 1);

        assertThat(settled.get(1L).unit).isEqualTo("MWh");
        assertThat(notes.get(1L).basis()).isEqualTo(UnitNamingStage.INFERRED);
    }

    @Test
    void aCurrencyAScaleOrNoAnswerIsNeverTakenForAUnit() {
        List<InterpretationCellView> cells = List.of(cell(1, "C1", 1, 3), cell(2, "C2", 2, 3), cell(3, "C3", 3, 3));
        Map<Long, String[]> labels = Map.of(
                1L, new String[] {"Revenue", "Y1"}, 2L, new String[] {"Cost", "Y1"}, 3L, new String[] {"Other", "Y1"});
        Map<Long, ReadingOutcome> settled = typed("", ReadingOutcome.DERIVED, 1, 2, 3);
        var notes = UnitNamingStage.run(cells, settled, context(labels), answering(p -> "{\"groups\":["
                + "{\"id\":1,\"unit\":\"lakh\",\"basis\":\"stated\",\"evidence\":\"Revenue\"},"
                + "{\"id\":2,\"unit\":\"INR\",\"basis\":\"inferred\",\"evidence\":\"Cost\"},"
                + "{\"id\":3,\"unit\":\"\",\"basis\":\"none\",\"evidence\":\"\"}]}", new ArrayList<>()), null, 1);

        assertThat(notes).isEmpty();
        assertThat(settled.values()).allMatch(o -> o.unit.isEmpty());
    }

    @Test
    void anInheritedUnitIsReplacedAndAnInputsOwnUnitIsLeftAlone() {
        List<InterpretationCellView> cells = List.of(cell(1, "D3", 3, 4), cell(2, "E3", 3, 5));
        Map<Long, String[]> labels = Map.of(
                1L, new String[] {"Annual Units", "Y1"}, 2L, new String[] {"Rooms", "Y1"});
        Map<Long, ReadingOutcome> settled = new HashMap<>();
        settled.put(1L, ReadingOutcome.typed(ReadingOutcome.QUANTITY, CellScale.UNIT, "day", "", ReadingOutcome.DERIVED));
        settled.put(2L, ReadingOutcome.typed(ReadingOutcome.COUNT, CellScale.UNIT, "room", "", ReadingOutcome.INPUT));
        List<String> prompts = new ArrayList<>();
        UnitNamingStage.run(cells, settled, context(labels), answering(
                p -> "{\"groups\":[{\"id\":1,\"unit\":\"kWh\",\"basis\":\"inferred\",\"evidence\":\"Annual Units\"}]}",
                prompts), null, 1);

        assertThat(settled.get(1L).unit).isEqualTo("kWh");
        assertThat(settled.get(2L).unit).isEqualTo("room");
        assertThat(prompts.get(0)).contains("Annual Units").doesNotContain("Rooms");
    }

    @Test
    void aFailedCallLeavesTheCellsAsTheyWereAndManyLabelsAreAskedInBatches() {
        List<InterpretationCellView> cells = new ArrayList<>();
        Map<Long, String[]> labels = new HashMap<>();
        Map<Long, ReadingOutcome> settled = new HashMap<>();
        for (int i = 1; i <= 65; i++) {
            cells.add(cell(i, "C" + i, i, 3));
            labels.put((long) i, new String[] {"Item " + i, "Y1"});
            settled.put((long) i, ReadingOutcome.typed(
                    ReadingOutcome.QUANTITY, CellScale.UNIT, "", "", ReadingOutcome.DERIVED));
        }
        List<String> prompts = new ArrayList<>();
        var notes = UnitNamingStage.run(cells, settled, context(labels), answering(p -> {
            throw new IllegalStateException("provider down");
        }, prompts), null, 1);

        assertThat(prompts).hasSize(3); // 30 + 30 + 5 labels
        assertThat(notes).isEmpty();
        assertThat(settled.values()).allMatch(o -> o.unit.isEmpty());
    }

    @Test
    void noModelMeansNoChange() {
        List<InterpretationCellView> cells = List.of(cell(1, "C1", 1, 3));
        Map<Long, ReadingOutcome> settled = typed("", ReadingOutcome.DERIVED, 1);
        var notes = UnitNamingStage.run(cells, settled, context(Map.of(1L, new String[] {"A", "B"})), null, null, 1);
        assertThat(notes).isEmpty();
        assertThat(settled.get(1L).unit).isEmpty();
    }
}
