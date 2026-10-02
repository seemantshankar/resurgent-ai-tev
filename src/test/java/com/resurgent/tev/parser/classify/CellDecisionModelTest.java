package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CellDecisionModelTest {

    private static InterpretationCellView cell(long id, String coord) {
        return new InterpretationCellView(
                id, 1L, coord, (int) id, 0, "number", "100", "100", "100", null, null, null, null, null,
                null, false, null, false, false, null, "input");
    }

    private static Map<Long, ReadingOutcome> untypable(long... ids) {
        Map<Long, ReadingOutcome> settled = new HashMap<>();
        for (long id : ids) {
            settled.put(id, ReadingOutcome.refused(ReadingOutcome.UNTYPABLE));
        }
        return settled;
    }

    /** Chat model that records whether it was asked at all. */
    private static final class CountingChat extends CellTypeClassifierLlmTest.FakeClassifierLlm {
        int calls;

        @Override
        public String classifyCellJson(String systemPrompt, String userPrompt, int maxTokens) {
            calls++;
            return super.classifyCellJson(systemPrompt, userPrompt, maxTokens);
        }
    }

    @Test
    void confidentDecisionSettlesCellWithoutChatModel() {
        var chat = new CountingChat();
        var classifier = new CellTypeClassifierLlm(null, chat)
                .withDecisionModel(state -> new CellDecisionClient.Decision("quantity", 0.97, "unit", 0.95));
        var settled = untypable(1L);

        classifier.classifyRemaining(List.of(cell(1L, "A1")), settled);

        assertThat(settled.get(1L).refusal).isNull();
        assertThat(settled.get(1L).kind).isEqualTo(ReadingOutcome.QUANTITY);
        assertThat(chat.calls).isZero();
    }

    @Test
    void lowConfidenceOrFailedDecisionFallsBackToChatModel() {
        var chat = new CountingChat();
        var classifier = new CellTypeClassifierLlm(null, chat)
                .withDecisionModel(state -> {
                    if (state.contains("A1")) {
                        return new CellDecisionClient.Decision("money", 0.99, "lakh", 0.50);
                    }
                    throw new IllegalStateException("HTTP 429");
                });
        var settled = untypable(1L, 2L);

        classifier.classifyRemaining(List.of(cell(1L, "A1"), cell(2L, "A2")), settled);

        assertThat(chat.calls).isPositive();
        assertThat(settled.get(1L).refusal).isNull();
        assertThat(settled.get(2L).refusal).isNull();
    }

    @Test
    void parsesDecisionsResponse() throws Exception {
        var d = OpenRouterDecisionClient.parse("""
                {"model":"liquid/d1-20260930","answers":{
                 "kind":{"type":"choice","choice":"money","probabilities":{"money":0.99},"confidence":0.994},
                 "scale":{"type":"choice","choice":"lakh","probabilities":{"lakh":0.93},"confidence":0.916}},
                 "usage":{"input_tokens":235,"output_tokens":0}}""");
        assertThat(d.kind()).isEqualTo("money");
        assertThat(d.scale()).isEqualTo("lakh");
        assertThat(d.confidence()).isEqualTo(0.916);
    }

    @Test
    void comparisonModeAsksBothButOnlyTheChatModelSettlesCells() throws Exception {
        var chat = new CountingChat(); // answers money / unit for everything
        var csv = java.nio.file.Files.createTempFile("d1cmp", ".csv");
        java.nio.file.Files.delete(csv);
        var classifier = new CellTypeClassifierLlm(null, chat)
                .withDecisionModel(state -> new CellDecisionClient.Decision("quantity", 0.97, "unit", 0.95))
                .withDecisionComparison(csv);
        var settled = untypable(1L);

        classifier.classifyRemaining(List.of(cell(1L, "A1")), settled);

        assertThat(chat.calls).isPositive();
        assertThat(settled.get(1L).kind).isEqualTo(ReadingOutcome.MONEY); // chat's answer, not D1's
        var lines = java.nio.file.Files.readAllLines(csv);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).contains("quantity").contains("money");
    }

    @Test
    void reportBucketsAgreementByConfidence() {
        var high = new CellDecisionClient.Decision("money", 0.99, "lakh", 0.98);
        var low = new CellDecisionClient.Decision("rate", 0.60, "unit", 0.90);
        var report = new DecisionComparison(List.of(
                new DecisionComparison.Row(1, "A1", "x", high, "money", "lakh"),
                new DecisionComparison.Row(2, "A2", "y", low, "money", "lakh"),
                new DecisionComparison.Row(3, "A3", "z", low, null, null))).report();

        assertThat(report).contains("3 cells compared").contains("chat left 1 untyped");
        assertThat(report).contains("rate -> money");
    }

    @Test
    void decisionBetweenOldAndNewThresholdNowGoesToChatModel() {
        var chat = new CountingChat();
        var classifier = new CellTypeClassifierLlm(null, chat)
                .withDecisionModel(state -> new CellDecisionClient.Decision("quantity", 0.85, "unit", 0.88));

        var settled = untypable(1L);
        classifier.classifyRemaining(List.of(cell(1L, "A1")), settled);

        assertThat(chat.calls).isPositive(); // 0.85 < the 0.90 default
        assertThat(settled.get(1L).kind).isEqualTo(ReadingOutcome.MONEY); // chat's answer

        var lenient = new CountingChat();
        var relaxed = new CellTypeClassifierLlm(null, lenient)
                .withDecisionModel(state -> new CellDecisionClient.Decision("quantity", 0.85, "unit", 0.88))
                .withDecisionTuning(0.80, 4);
        var settled2 = untypable(1L);
        relaxed.classifyRemaining(List.of(cell(1L, "A1")), settled2);

        assertThat(lenient.calls).isZero();
        assertThat(settled2.get(1L).kind).isEqualTo(ReadingOutcome.QUANTITY);
    }
}
