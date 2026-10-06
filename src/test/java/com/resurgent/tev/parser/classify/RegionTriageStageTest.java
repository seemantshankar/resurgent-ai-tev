package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.parser.classify.RegionTriageClient.Opinion;
import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegionTriageStageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Opinion opinion(String choice, double confidence) {
        return new Opinion(choice, confidence, Map.of());
    }

    private static RegionTriageStage.Summary summary(int numeric, int text) {
        return new RegionTriageStage.Summary("{}", numeric, text);
    }

    @Test
    void aScratchRegionWithNumbersGoesToLayerAUnlessTheOpinionConfirmsScratch() {
        var withNumbers = summary(300, 50);
        assertThat(RegionTriageStage.escalates("scratch", withNumbers, opinion("main", 0.98))).isTrue();
        assertThat(RegionTriageStage.escalates("scratch", withNumbers, opinion("scratch", 0.69))).isTrue();
        assertThat(RegionTriageStage.escalates("scratch", withNumbers, null)).isTrue();
        assertThat(RegionTriageStage.escalates("scratch", withNumbers, opinion("scratch", 0.70))).isFalse();
        assertThat(RegionTriageStage.escalates("scratch", withNumbers, opinion("orphan", 0.9))).isFalse();
    }

    @Test
    void aScratchRegionOfAFewLabelsOrNotesIsLeftAloneButABlockOfTextIsNot() {
        assertThat(RegionTriageStage.escalates("scratch", summary(0, 1), opinion("main", 0.9))).isFalse();
        assertThat(RegionTriageStage.escalates("scratch", summary(0, 2), null)).isFalse();
        assertThat(RegionTriageStage.escalates("scratch", summary(0, 3), opinion("main", 0.5))).isTrue();
        assertThat(RegionTriageStage.escalates("scratch", summary(0, 54), opinion("scratch", 0.9))).isFalse();
    }

    @Test
    void onlyScratchRegionsAreEverEscalated() {
        assertThat(RegionTriageStage.escalates("main", summary(10, 10), opinion("scratch", 0.99))).isFalse();
        assertThat(RegionTriageStage.escalates("helper", summary(10, 10), null)).isFalse();
    }

    private static InterpretationCellView cell(
            long id, int row, int col, String type, String text, String number, String formula) {
        String coord = HeaderGeometry.columnLetters(col) + row;
        return new InterpretationCellView(id, 1L, coord, row, col, type, text, text != null ? text : number,
                number, null, null, formula, null, null, null, false, null, false, false, null, null);
    }

    private static CandidateRow region(int r0, int c0, int r1, int c1) {
        return new CandidateRow(7L, 1L, 1L, "child", 1L, r0, c0, r1, c1, null, null, null, false, null, null,
                null, "2026-10-06T00:00:00Z", "scratch");
    }

    @Test
    void theSummaryTellsTheModelWhatIsInTheRegionAndWhatLabelsSitToItsLeft() throws Exception {
        // Labels sit in column B, outside the region D2:E3, as in the growth-rate block of a real sheet.
        List<InterpretationCellView> cells = List.of(
                cell(1, 1, 4, "text", "FY24", null, null),
                cell(2, 2, 2, "text", "Mail revenue", null, null),
                cell(3, 3, 2, "text", "RSD revenue", null, null),
                cell(4, 2, 4, "number", null, "0.25", "(F2-E2)/E2"),
                cell(5, 2, 5, "number", null, "0.10", "(G2-F2)/F2"),
                cell(6, 3, 4, "number", null, "0.0", "(F3-E3)/E3"),
                cell(7, 3, 5, "number", null, "0.30", "'Other'!A1/2"));
        var summary = RegionTriageStage.summarize(
                new RegionTriageStage.Region(region(2, 4, 3, 5), "Assumptions", cells),
                new RegionTriageStage.SheetIndex(cells));
        assertThat(summary.numericCells()).isEqualTo(4);
        assertThat(summary.textCells()).isZero();
        JsonNode state = MAPPER.readTree(summary.stateJson());
        assertThat(state.path("region").asText()).isEqualTo("D2:E3");
        assertThat(state.path("sheet").asText()).isEqualTo("Assumptions");
        assertThat(state.path("numeric_cells_with_a_text_label_to_their_left").asText()).isEqualTo("4 of 4");
        assertThat(state.path("formulas_reading_other_sheets").asInt()).isEqualTo(1);
        assertThat(state.path("numeric_cells_near_zero").asText()).isEqualTo("1 of 4");
        assertThat(state.path("row_labels_and_text").toString()).contains("Mail revenue", "RSD revenue");
        assertThat(state.path("column_headers_above").toString()).contains("FY24");
        // The first-pass label is never part of the state.
        assertThat(summary.stateJson()).doesNotContain("scratch").doesNotContain("first_pass");
    }

    @Test
    void theRequestAsksOneTriageQuestionWithThreeOptions() throws Exception {
        JsonNode body = MAPPER.readTree(OpenRouterRegionTriageClient.requestBody("liquid/d1", "{\"sheet\":\"S\"}"));
        assertThat(body.path("model").asText()).isEqualTo("liquid/d1");
        assertThat(body.path("state").path("sheet").asText()).isEqualTo("S");
        JsonNode question = body.path("questions").path("triage");
        assertThat(question.path("type").asText()).isEqualTo("choice");
        assertThat(question.path("criteria").fieldNames()).toIterable().containsExactly("main", "scratch", "orphan");
    }

    @Test
    void theAnswerIsReadAsAChoiceWithItsConfidence() throws Exception {
        Opinion o = OpenRouterRegionTriageClient.parse("{\"answers\":{\"triage\":{\"choice\":\"Scratch\","
                + "\"confidence\":0.81,\"probabilities\":{\"main\":0.1,\"scratch\":0.89,\"orphan\":0.01}}}}");
        assertThat(o.choice()).isEqualTo("scratch");
        assertThat(o.confidence()).isEqualTo(0.81);
        assertThat(o.probabilities()).containsEntry("scratch", 0.89);
    }
}
