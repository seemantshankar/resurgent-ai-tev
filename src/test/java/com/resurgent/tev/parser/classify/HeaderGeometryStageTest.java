package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeaderGeometryStageTest {

    private static final long WS = 100L;
    private static final CandidateRow REGION = new CandidateRow(
            10L, 1L, WS, "child", null, 2, 1, 29, 7,
            null, null, null, false, null, null, null, "2026-01-01T00:00:00Z", null);

    @Test
    void gridShowsTextVerbatimMergesOnceAndNumbersAsHashesExceptYears() {
        List<InterpretationCellView> cells = List.of(
                cell("B4", 4, 2, "text", "Area of floors", "Area of floors", null, true, false, "B4:C4"),
                cell("C4", 4, 3, "empty", null, "Area of floors", null, false, true, "B4:C4"),
                cell("D5", 5, 4, "number", null, "2025", "2025", false, false, null),
                cell("C8", 8, 3, "number", null, "284.11", "284.11", false, false, null),
                cell("D9", 9, 4, "error", "#REF!", "#REF!", null, false, false, null));

        String grid = HeaderGeometryStage.render(REGION, "area statement", cells);

        assertThat(grid).contains("area statement").contains("B4:C4=\"Area of floors\"");
        assertThat(grid.split("Area of floors", -1)).hasSize(2); // the participant repeats the anchor; shown once
        assertThat(grid).contains("D5=2025");                // a year is a header, not an amount
        assertThat(grid).contains("C8=#").doesNotContain("284.11");
        assertThat(grid).contains("D9=#ERR");
    }

    @Test
    void aRunningSequenceIsShownAsItselfBecauseItIsAPeriodLabelNotAnAmount() {
        List<InterpretationCellView> cells = List.of(
                cell("A8", 8, 1, "number", null, "1", "1", false, false, null),
                cell("A9", 9, 1, "number", null, "2", "2", false, false, null),
                cell("A10", 10, 1, "number", null, "3", "3", false, false, null),
                cell("C8", 8, 3, "number", null, "7", "7", false, false, null),   // lone amount, not a sequence
                cell("C10", 10, 3, "number", null, "9", "9", false, false, null));

        String grid = HeaderGeometryStage.render(REGION, "s", cells);

        assertThat(grid).contains("A8=1").contains("A9=2").contains("A10=3");
        assertThat(grid).contains("C8=#").contains("C10=#");
    }

    @Test
    void promptDoesNotTellTheModelToDropNumberedLabelColumns() {
        assertThat(HeaderGeometryStage.SYSTEM)
                .contains("Numbers that count")
                .doesNotContain("Leave out serial-number columns");
    }

    @Test
    void parsesBandsAndLabelColumnsAndDropsWhatLiesOutsideTheRegion() {
        String json = "{\"bands\":[\"B4:G7\",\"A40:G42\",\"B1:G30\"],\"rowLabelColumns\":[\"B\",\"Z\"]}";

        HeaderGeometry g = HeaderGeometryStage.parse(json, REGION).orElseThrow();

        assertThat(g.candidateId()).isEqualTo(10L);
        assertThat(g.bands()).containsExactly(new HeaderGeometry.Band(4, 7, 2, 7)); // outside / too tall dropped
        assertThat(g.rowLabelColumns()).containsExactly(2);
    }

    @Test
    void parsesAnnotationAndGroupColumnsAndKeepsThemInRange() {
        String json = "{\"bands\":[\"B4:G7\"],\"rowLabelColumns\":[\"B\"],"
                + "\"annotationColumns\":[\"G\",\"Z\"],\"groupColumns\":[\"A\"]}";

        HeaderGeometry g = HeaderGeometryStage.parse(json, REGION).orElseThrow();

        assertThat(g.annotationColumns()).containsExactly(7);
        assertThat(g.groupColumns()).containsExactly(1);
    }

    @Test
    void anUnusableAnswerGivesNoGeometryRatherThanAGuess() {
        assertThat(HeaderGeometryStage.parse("not json", REGION)).isEmpty();
        assertThat(HeaderGeometryStage.parse("{\"bands\":[],\"rowLabelColumns\":[]}", REGION)).isEmpty();
        assertThat(HeaderGeometryStage.parse("```json\n{\"bands\":[\"B4:C5\"]}\n```", REGION)).isPresent();
    }

    @Test
    void asksOncePerRegionAndSkipsRegionsWhoseCallFailsOrAnswersNothingUseful() {
        CandidateRow second = new CandidateRow(
                11L, 1L, WS, "child", null, 2, 1, 29, 7,
                null, null, null, false, null, null, null, "2026-01-01T00:00:00Z", null);
        List<String> prompts = new ArrayList<>();
        ClassifierLlm llm = new FakeLlm() {
            @Override
            public String classifyCellJson(String system, String user, int maxTokens) {
                prompts.add(user);
                if (user.contains("region 11")) {
                    throw new IllegalStateException("model down");
                }
                return "{\"bands\":[\"B4:C5\"],\"rowLabelColumns\":[\"A\"]}";
            }
        };

        Map<Long, HeaderGeometry> out = HeaderGeometryStage.ask(
                List.of(
                        new HeaderGeometryStage.Region(REGION, "s", List.of()),
                        new HeaderGeometryStage.Region(second, "s", List.of())),
                llm, 1);

        assertThat(prompts).hasSize(2);
        assertThat(out).containsOnlyKeys(10L);
    }

    private static InterpretationCellView cell(
            String coord, int row, int col, String type, String text, String display, String numeric,
            boolean anchor, boolean participant, String range) {
        return new InterpretationCellView(
                row * 100L + col, WS, coord, row, col, type, text, display, numeric, null, null, null, null, null,
                null, "error".equals(type), "error".equals(type) ? "#REF!" : null, anchor, participant, range, "cell");
    }

    abstract static class FakeLlm implements ClassifierLlm {
        @Override
        public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
            return List.of();
        }

        @Override
        public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
            throw new UnsupportedOperationException();
        }
    }
}
