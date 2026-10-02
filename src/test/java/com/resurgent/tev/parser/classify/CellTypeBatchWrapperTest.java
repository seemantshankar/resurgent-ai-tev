package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The real batch parser accepts every wrapper shape a model is seen to return. */
class CellTypeBatchWrapperTest {

    private static final String ANSWER =
            "{\"cell\":1,\"kind\":\"money\",\"scale\":\"lakh\",\"unit\":\"\",\"currency\":\"INR\",\"confidence\":0.95}";

    private static ClassifierLlm answering(String json) {
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
                return json;
            }
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"results\":[%s]}",
        "{\"cells\":[%s]}",
        "{\"array\":[%s]}",
        "{\"data\":[%s]}",
        "{\"somethingElse\":[%s]}", // any object holding one array
        "[%s]" // bare array
    })
    void aBatchAnswerIsReadWhicheverWayItIsWrapped(String template) {
        var cell = new InterpretationCellView(
                1L, 1L, "A1", 0, 0, "number", "100", "100", "100", null, null, null, null, null,
                null, false, null, false, false, null, "input");
        Map<Long, ReadingOutcome> settled = new HashMap<>();
        settled.put(1L, ReadingOutcome.refused(ReadingOutcome.UNTYPABLE));

        new CellTypeClassifierLlm(null, answering(template.formatted(ANSWER)))
                .classifyRemaining(List.of(cell), settled);

        assertThat(settled.get(1L).refusal).isNull();
        assertThat(settled.get(1L).kind).isEqualTo(ReadingOutcome.MONEY);
        assertThat(settled.get(1L).scale).isEqualTo(CellScale.LAKH);
    }
}
