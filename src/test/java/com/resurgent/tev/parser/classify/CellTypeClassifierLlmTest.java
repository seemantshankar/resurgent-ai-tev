package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CellTypeClassifierLlm}. */
class CellTypeClassifierLlmTest {

    @Test
    void classifyRemaining_ignoresAlreadyTyped() {
        // Cells already typed should not be classified again
        var classifier = new CellTypeClassifierLlm(null, new FakeClassifierLlm());
        // InterpretationCellView(cellId, worksheetId, coord, rowNum, colNum, valueType, textValue,
        // displayValue, numericValue, boolValue, dateValue, formulaText, formulaState, cachedValue,
        // cacheState, isError, errorType, isMergedAnchor, isMergedParticipant, mergedRange, valueSource)
        var cell = new InterpretationCellView(
                1L, 1L, "A1", 0, 0, "number", "100", "100", "100", null, null, null, null, null,
                null, false, null, false, false, null, "input");
        ReadingOutcome typed =
                ReadingOutcome.typed(ReadingOutcome.MONEY, CellScale.UNIT, "", "INR", "input");
        var settled = Map.of(1L, typed);

        classifier.classifyRemaining(List.of(cell), settled);

        // Settled map should be unchanged
        assertThat(settled.get(1L)).isEqualTo(typed);
    }

    @Test
    void classifyRemaining_skipErrorCells() {
        // Error cells should not be classified
        var classifier = new CellTypeClassifierLlm(null, new FakeClassifierLlm());
        var cell = new InterpretationCellView(
                1L, 1L, "A1", 0, 0, "error", "#DIV/0!", "#DIV/0!", null, null, null, null, null,
                null, null, true, "DIV_BY_ZERO", false, false, null, "input");
        var settled = Map.of(1L, ReadingOutcome.refused(ReadingOutcome.UNTYPABLE));

        classifier.classifyRemaining(List.of(cell), settled);

        // Cell should remain UNTYPABLE
        assertThat(settled.get(1L).refusal).isEqualTo(ReadingOutcome.UNTYPABLE);
    }

    @Test
    void classifyRemaining_skipNonNumericCells() {
        // Non-numeric cells should not be classified
        var classifier = new CellTypeClassifierLlm(null, new FakeClassifierLlm());
        var cell = new InterpretationCellView(
                1L, 1L, "A1", 0, 0, "text", "Label", "Label", null, null, null, null, null, null,
                null, false, null, false, false, null, "input");
        var settled = Map.of(1L, ReadingOutcome.refused(ReadingOutcome.UNTYPABLE));

        classifier.classifyRemaining(List.of(cell), settled);

        // Cell should remain UNTYPABLE
        assertThat(settled.get(1L).refusal).isEqualTo(ReadingOutcome.UNTYPABLE);
    }

    @Test
    void classifyRemaining_lowConfidenceStaysUntypable() {
        // LLM response with low confidence should not update settled map
        var classifier = new CellTypeClassifierLlm(null, new FakeClassifierLlm("0.5"));
        var cell = new InterpretationCellView(
                1L, 1L, "A1", 0, 0, "number", "100", "100", "100", null, null, null, null, null,
                null, false, null, false, false, null, "input");
        var settled = Map.<Long, ReadingOutcome>of(1L, ReadingOutcome.refused(ReadingOutcome.UNTYPABLE));

        classifier.classifyRemaining(List.of(cell), settled);

        // Cell should remain UNTYPABLE (low confidence, below 0.80 threshold)
        assertThat(settled.get(1L).refusal).isEqualTo(ReadingOutcome.UNTYPABLE);
    }

    private static InterpretationCellView textCell(long id, int row, int col, String text) {
        return new InterpretationCellView(
                id, 1L, "T" + id, row, col, "text", text, text, null, null, null, null, null, null,
                null, false, null, false, false, null, "input");
    }

    private static InterpretationCellView numberCell(long id, int row, int col) {
        return new InterpretationCellView(
                id, 1L, "N" + id, row, col, "number", "100", "100", "100", null, null, null, null, null,
                null, false, null, false, false, null, "input");
    }

    @Test
    void deterministicPass_readsLacsAsLakhScale() {
        var classifier = new CellTypeClassifierLlm(null, new FakeClassifierLlm());
        var cells = List.of(
                textCell(1L, 2, 1, "Room revenue"),
                textCell(2L, 1, 2, "TOTAL ROOM SALES(Rs. In Lacs)"),
                numberCell(3L, 2, 2));
        var settled = new HashMap<Long, ReadingOutcome>();
        settled.put(3L, ReadingOutcome.refused(ReadingOutcome.UNTYPABLE));

        classifier.classifyRemaining(cells, settled);

        assertThat(settled.get(3L).kind).isEqualTo(ReadingOutcome.MONEY);
        assertThat(settled.get(3L).scale).isEqualTo(CellScale.LAKH);
        assertThat(settled.get(3L).currency).isEqualTo("INR");
    }

    @Test
    void deterministicPass_readsLakhsCroreAndMillionScales() {
        for (var spec : List.of(
                new String[] {"Amount (Rs. in Lakhs)", "LAKH"},
                new String[] {"Amount (Rs. Crores)", "CRORE"},
                new String[] {"Amount (Rs. in Million)", "MILLION"},
                new String[] {"Amount (Rs.)", "UNIT"})) {
            var classifier = new CellTypeClassifierLlm(null, new FakeClassifierLlm());
            var cells = List.of(textCell(1L, 2, 1, "Item"), textCell(2L, 1, 2, spec[0]), numberCell(3L, 2, 2));
            var settled = new HashMap<Long, ReadingOutcome>();
            settled.put(3L, ReadingOutcome.refused(ReadingOutcome.UNTYPABLE));

            classifier.classifyRemaining(cells, settled);

            assertThat(settled.get(3L).scale).as(spec[0]).isEqualTo(CellScale.valueOf(spec[1]));
        }
    }

    /** Fake {@link ClassifierLlm} for testing. */
    static class FakeClassifierLlm implements ClassifierLlm {
        private final String confidence;

        FakeClassifierLlm() {
            this("0.95");
        }

        FakeClassifierLlm(String confidence) {
            this.confidence = confidence;
        }

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
            // Return a fake JSON response for cell type classification
            return String.format(
                    """
                    {
                      "kind": "money",
                      "scale": "unit",
                      "unit": "",
                      "currency": "INR",
                      "confidence": %s
                    }
                    """,
                    confidence);
        }
    }
}
