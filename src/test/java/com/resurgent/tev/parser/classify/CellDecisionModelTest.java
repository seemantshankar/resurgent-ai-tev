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
    void aNonMoneyKindSettlesOnItsKindConfidenceAloneAndNeverCarriesAScale() {
        var chat = new CountingChat();
        var classifier = new CellTypeClassifierLlm(null, chat)
                .withDecisionModel(state -> new CellDecisionClient.Decision("percent", 0.97, "lakh", 0.30));
        var settled = untypable(1L);

        classifier.classifyRemaining(List.of(cell(1L, "A1")), settled);

        assertThat(chat.calls).isZero();
        assertThat(settled.get(1L).kind).isEqualTo(ReadingOutcome.PERCENT);
        assertThat(settled.get(1L).scale).isEqualTo(CellScale.UNIT); // the model's "lakh" means nothing for a percent
    }

    @Test
    void moneyWhoseScaleTheSheetStatesSettlesOnKindAloneAndTheScaleQuestionIsNotAsked() {
        var chat = new CountingChat();
        List<Boolean> askedScale = new java.util.ArrayList<>();
        var classifier = new CellTypeClassifierLlm(null, chat)
                .withDecisionModel(new CellDecisionClient() {
                    @Override public Decision decide(String state) {
                        throw new AssertionError("production asks through decide(state, askScale)");
                    }
                    @Override public Decision decide(String state, boolean askScale) {
                        askedScale.add(askScale);
                        return new Decision("money", 0.97, null, 0.0);
                    }
                });
        CellContext context = new CellContext() {
            @Override public String rowLabel(InterpretationCellView c) { return "Land"; }
            @Override public String columnLabel(InterpretationCellView c) { return ""; }
            @Override public RegionContext region(InterpretationCellView c) { return RegionContext.NONE; }
            @Override public CellScale statedScale(InterpretationCellView c) { return CellScale.LAKH; }
            @Override public String workbookKey() { return ""; }
        };
        var settled = untypable(1L);

        classifier.classifyRemaining(List.of(cell(1L, "A1")), settled, context);

        assertThat(askedScale).containsExactly(false);
        assertThat(chat.calls).isZero();
        assertThat(settled.get(1L).kind).isEqualTo(ReadingOutcome.MONEY);
        assertThat(settled.get(1L).scale).isEqualTo(CellScale.LAKH);
    }

    @Test
    void moneyWithNoStatedScaleStillNeedsTheScaleConfidence() {
        var chat = new CountingChat();
        var classifier = new CellTypeClassifierLlm(null, chat)
                .withDecisionModel(state -> new CellDecisionClient.Decision("money", 0.97, "lakh", 0.40));

        classifier.classifyRemaining(List.of(cell(1L, "A1")), untypable(1L));

        assertThat(chat.calls).isPositive();
    }

    @Test
    void theScaleQuestionIsLeftOutOfTheRequestWhenNotAskedAndItsMissingAnswerParses() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        var without = mapper.readTree(OpenRouterDecisionClient.requestBody("m", "{\"cell\":{}}", false));
        var with = mapper.readTree(OpenRouterDecisionClient.requestBody("m", "{\"cell\":{}}", true));
        var d = OpenRouterDecisionClient.parse("""
                {"answers":{"kind":{"type":"choice","choice":"money","confidence":0.95}}}""", false);

        assertThat(without.path("questions").has("scale")).isFalse();
        assertThat(without.path("questions").has("kind")).isTrue();
        assertThat(with.path("questions").has("scale")).isTrue();
        assertThat(d.kind()).isEqualTo("money");
        assertThat(d.scale()).isNull();
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
    void decisionStateIsAStructuredObjectCarryingTheCellsFullContext() throws Exception {
        List<String> states = new java.util.ArrayList<>();
        var classifier = new CellTypeClassifierLlm(null, new CountingChat())
                .withDecisionModel(state -> {
                    states.add(state);
                    return new CellDecisionClient.Decision("quantity", 0.97, "unit", 0.95);
                });
        CellContext context = new CellContext() {
            @Override public String rowLabel(InterpretationCellView c) { return "Site development"; }
            @Override public String columnLabel(InterpretationCellView c) { return "Phase 1"; }
            @Override public List<String> rowNotes(InterpretationCellView c) { return List.of("Exempted"); }
            @Override public String partOf(InterpretationCellView c) { return "Mezzanine floor"; }
            @Override public RegionContext region(InterpretationCellView c) {
                return new RegionContext(5L, "project_summary", "Area statement of floors.", "Area statement", "area", true);
            }
            @Override public String workbookKey() { return ""; }
        };

        classifier.classifyRemaining(List.of(cell(1L, "A1")), untypable(1L), context);

        var state = new com.fasterxml.jackson.databind.ObjectMapper().readTree(states.get(0));
        assertThat(state.path("cell").path("coord").asText()).isEqualTo("A1");
        assertThat(state.path("row_label").asText()).isEqualTo("Site development");
        assertThat(state.path("column_label").asText()).isEqualTo("Phase 1");
        assertThat(state.path("part_of").asText()).isEqualTo("Mezzanine floor");
        assertThat(state.path("row_note").get(0).asText()).isEqualTo("Exempted");
        assertThat(state.path("region").path("about").asText()).isEqualTo("Area statement of floors.");
        assertThat(state.path("region").path("family").asText()).isEqualTo("project_summary");
    }

    // ---- the facts the model is given, beyond the region ------------------------------------

    private static InterpretationCellView formulaCell(long id, String coord, String formula) {
        return new InterpretationCellView(
                id, 1L, coord, (int) id, 0, "number", "100", "100", "100", null, null, formula, null, null,
                null, false, null, false, false, null, "formula");
    }

    private static CellContext sheetContext(String sheet, CellScale stated, Map<Long, String> rowLabels) {
        return new CellContext() {
            @Override public String rowLabel(InterpretationCellView c) { return rowLabels.getOrDefault(c.cellId(), ""); }
            @Override public String columnLabel(InterpretationCellView c) { return ""; }
            @Override public RegionContext region(InterpretationCellView c) { return RegionContext.NONE; }
            @Override public CellScale statedScale(InterpretationCellView c) { return stated; }
            @Override public String sheetName(InterpretationCellView c) { return sheet; }
            @Override public String workbookKey() { return ""; }
        };
    }

    /** The state sent for the first cell asked about, as JSON. */
    private static com.fasterxml.jackson.databind.JsonNode stateFor(
            CellTypeClassifierLlm classifier, List<InterpretationCellView> cells, CellContext context,
            Map<Long, ReadingOutcome> settled) throws Exception {
        List<String> states = new java.util.ArrayList<>();
        classifier.withDecisionModel(state -> {
            states.add(state);
            return new CellDecisionClient.Decision("quantity", 0.97, "unit", 0.95);
        }).classifyRemaining(cells, settled, context);
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(states.get(0));
    }

    @Test
    void theStateCarriesTheSheetAndTheStatedScaleWithOrWithoutARegion() throws Exception {
        var state = stateFor(new CellTypeClassifierLlm(null, new CountingChat()), List.of(cell(1L, "A1")),
                sheetContext("P  L ", CellScale.LAKH, Map.of()), untypable(1L));

        assertThat(state.path("sheet").asText()).isEqualTo("P  L");
        assertThat(state.path("stated_scale").asText()).isEqualTo("lakh");
        assertThat(state.has("region")).isFalse();
    }

    @Test
    void aFormulaCellCarriesTheKindsOfTheCellsItReadsAndNotTheUntypedOnes() throws Exception {
        var sales = cell(2L, "B2");
        var untyped = cell(3L, "B3");
        var formula = formulaCell(1L, "B1", "B2*B3");
        Map<Long, ReadingOutcome> settled = untypable(1L, 3L);
        settled.put(2L, ReadingOutcome.typed("money", CellScale.LAKH, "", "INR", ReadingOutcome.INPUT));
        var classifier = new CellTypeClassifierLlm(null, new CountingChat())
                .withPrecedents(Map.of(1L, java.util.Set.of(2L, 3L)),
                        Map.of(1L, formula, 2L, sales, 3L, untyped));

        var state = stateFor(classifier, List.of(formula), sheetContext("Calc", null, Map.of(2L, "Net sales")), settled);

        var inputs = state.path("inputs");
        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0).path("ref").asText()).isEqualTo("B2");
        assertThat(inputs.get(0).path("kind").asText()).isEqualTo("money");
        assertThat(inputs.get(0).path("scale").asText()).isEqualTo("lakh");
        assertThat(inputs.get(0).path("row_label").asText()).isEqualTo("Net sales");
    }

    @Test
    void aLongPrecedentListIsCutAndCounted() throws Exception {
        var formula = formulaCell(1L, "B1", "SUM(C1:C20)");
        Map<Long, InterpretationCellView> byId = new HashMap<>();
        Map<Long, ReadingOutcome> settled = untypable(1L);
        java.util.Set<Long> preds = new java.util.TreeSet<>();
        byId.put(1L, formula);
        for (long id = 2; id <= 21; id++) {
            byId.put(id, cell(id, "C" + id));
            preds.add(id);
            settled.put(id, ReadingOutcome.typed("money", CellScale.LAKH, "", "INR", ReadingOutcome.INPUT));
        }
        var classifier = new CellTypeClassifierLlm(null, new CountingChat()).withPrecedents(Map.of(1L, preds), byId);

        var state = stateFor(classifier, List.of(formula), sheetContext("Calc", null, Map.of()), settled);

        assertThat(state.path("inputs")).hasSize(8);
        assertThat(state.path("inputs_not_shown").asInt()).isEqualTo(12);
    }

    @Test
    void anInputCellHasNoInputsAndTheFactsCanBeSwitchedOff() throws Exception {
        var plain = stateFor(new CellTypeClassifierLlm(null, new CountingChat()), List.of(cell(1L, "A1")),
                sheetContext("P  L ", CellScale.LAKH, Map.of()), untypable(1L));
        var legacy = stateFor(
                new CellTypeClassifierLlm(null, new CountingChat()).withDecisionStateFacts(false, false, false),
                List.of(cell(1L, "A1")), sheetContext("P  L ", CellScale.LAKH, Map.of()), untypable(1L));

        assertThat(plain.has("inputs")).isFalse();
        assertThat(legacy.has("sheet")).isFalse();
        assertThat(legacy.has("stated_scale")).isFalse();
    }

    @Test
    void theQuestionsNameOnlyWhatIsGivenAndDoNotInviteReadingWhatTheCellSays() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var body = mapper.readTree(OpenRouterDecisionClient.requestBody("m", "{\"cell\":{}}", true));
        var legacy = mapper.readTree(OpenRouterDecisionClient.requestBody("m", "{\"cell\":{}}", true, true));

        assertThat(body.path("questions").path("scale").path("instructions").asText())
                .doesNotContain("if none does").doesNotContain("says");
        assertThat(body.path("questions").path("kind").path("instructions").asText()).contains("inputs");
        assertThat(legacy.path("questions").path("scale").path("instructions").asText()).contains("if none does");
    }

    @Test
    void theBuildThatAnsweredIsRecordedWithTheRun() {
        LlmStats.GLOBAL.reset();
        var client = new OpenRouterDecisionClient("key", "liquid/d1");

        client.noteServedModel("liquid/d1-20260930");

        assertThat(LlmStats.GLOBAL.stats()).anySatisfy(stat -> {
            assertThat(stat.name()).isEqualTo("decision_model_served");
            assertThat(stat.text()).isEqualTo("liquid/d1-20260930");
        });
        LlmStats.GLOBAL.reset();
    }

    @Test
    void structuredStateGoesToTheDecisionsApiAsAnObjectAndPlainTextStaysText() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        var asObject = mapper.readTree(OpenRouterDecisionClient.requestBody("m", "{\"cell\":{\"coord\":\"A1\"}}"));
        var asText = mapper.readTree(OpenRouterDecisionClient.requestBody("m", "Cell: A1"));

        assertThat(asObject.path("state").isObject()).isTrue();
        assertThat(asObject.path("state").path("cell").path("coord").asText()).isEqualTo("A1");
        assertThat(asText.path("state").isTextual()).isTrue();
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
    void shadowCsvCarriesTheContextEachDecisionWasMadeFromAndItsRunnerUp() throws Exception {
        var csv = java.nio.file.Files.createTempFile("d1ctx", ".csv");
        java.nio.file.Files.delete(csv);
        var classifier = new CellTypeClassifierLlm(null, new CountingChat())
                .withDecisionModel(state -> new CellDecisionClient.Decision(
                        "money", 0.97, "lakh", 0.95, java.util.Map.of("money", 0.93, "rate", 0.05, "ratio", 0.02)))
                .withDecisionComparison(csv);
        CellContext context = new CellContext() {
            @Override public String rowLabel(InterpretationCellView c) { return "Land"; }
            @Override public String columnLabel(InterpretationCellView c) { return ""; }
            @Override public RegionContext region(InterpretationCellView c) { return RegionContext.NONE; }
            @Override public CellScale statedScale(InterpretationCellView c) { return CellScale.LAKH; }
            @Override public String sheetName(InterpretationCellView c) { return "P  L "; }
            @Override public String workbookKey() { return ""; }
        };

        classifier.classifyRemaining(List.of(cell(1L, "A1")), untypable(1L), context);

        var lines = java.nio.file.Files.readAllLines(csv);
        assertThat(lines.get(0)).contains("sheet,has_region,stated_scale,d1_kind_top2");
        assertThat(lines.get(1)).contains("\"P  L \",false,lakh,\"money:0.93|rate:0.05\"");
    }

    @Test
    void parsesTheKindProbabilitiesOfADecisionsResponse() throws Exception {
        var d = OpenRouterDecisionClient.parse("""
                {"answers":{
                 "kind":{"type":"choice","choice":"money","probabilities":{"money":0.7,"rate":0.2,"count":0.1},"confidence":0.6},
                 "scale":{"type":"choice","choice":"unit","confidence":0.9}}}""");
        assertThat(d.kindProbabilities()).containsEntry("rate", 0.2).containsEntry("money", 0.7);
    }

    @Test
    void reportBucketsAgreementByConfidence() {
        var high = new CellDecisionClient.Decision("money", 0.99, "lakh", 0.98);
        var low = new CellDecisionClient.Decision("rate", 0.60, "unit", 0.90);
        var report = new DecisionComparison(List.of(
                new DecisionComparison.Row(1, "A1", "x", "col", high, "money", "lakh"),
                new DecisionComparison.Row(2, "A2", "y", "col", low, "money", "lakh"),
                new DecisionComparison.Row(3, "A3", "z", "col", low, null, null))).report();

        assertThat(report).contains("3 cells compared").contains("chat left 1 untyped");
        assertThat(report).contains("rate -> money");
    }

    @Test
    void aDecisionBelowTheDefaultThresholdGoesToChatModel() {
        var chat = new CountingChat();
        var classifier = new CellTypeClassifierLlm(null, chat)
                .withDecisionModel(state -> new CellDecisionClient.Decision("quantity", 0.65, "unit", 0.70));

        var settled = untypable(1L);
        classifier.classifyRemaining(List.of(cell(1L, "A1")), settled);

        assertThat(chat.calls).isPositive(); // 0.65 < the 0.75 default
        assertThat(settled.get(1L).kind).isEqualTo(ReadingOutcome.MONEY); // chat's answer

        var lenient = new CountingChat();
        var relaxed = new CellTypeClassifierLlm(null, lenient)
                .withDecisionModel(state -> new CellDecisionClient.Decision("quantity", 0.65, "unit", 0.70))
                .withDecisionTuning(0.60, 4);
        var settled2 = untypable(1L);
        relaxed.classifyRemaining(List.of(cell(1L, "A1")), settled2);

        assertThat(lenient.calls).isZero();
        assertThat(settled2.get(1L).kind).isEqualTo(ReadingOutcome.QUANTITY);
    }

    @Test
    void shadowModeCountsFailedDecisionCalls() {
        LlmStats.GLOBAL.reset();
        var classifier = new CellTypeClassifierLlm(null, new CountingChat())
                .withDecisionModel(state -> {
                    throw new IllegalStateException("HTTP 500");
                })
                .withDecisionComparison(null);

        classifier.classifyRemaining(List.of(cell(1L, "A1")), untypable(1L));

        assertThat(LlmStats.GLOBAL.stat("layer-b", "cells_decision_failed")).isEqualTo(1.0);
        LlmStats.GLOBAL.reset();
    }
}
