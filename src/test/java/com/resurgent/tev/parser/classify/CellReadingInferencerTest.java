package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CellReadingInferencer} - tests inference rules in isolation. */
class CellReadingInferencerTest {

    @Test
    void totalRowInfersFromTypedNeighbors() {
        // Row 5 has "Total" label in column A
        // Cells B3 and B7 (within 5-row range) are typed as money
        // B5 is untypable numeric cell
        // Result: B5 should be inferred as money since neighbors agree
        List<InterpretationCellView> cells = new ArrayList<>();
        cells.add(cell(1, 5, 1, "Total", "text"));
        cells.add(cell(2, 3, 2, "100", "number", "100"));
        cells.add(cell(3, 5, 2, "?", "number", null)); // untypable
        cells.add(cell(4, 7, 2, "200", "number", "200"));

        Map<Long, ReadingOutcome> settled = new HashMap<>();
        settled.put(2L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(3L, ReadingOutcome.refused("untypable"));
        settled.put(4L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));

        new CellReadingInferencer(cells).infer(settled);

        ReadingOutcome inferred = settled.get(3L);
        assertThat(inferred.typed()).isTrue();
        assertThat(inferred.kind).isEqualTo("money");
        assertThat(inferred.scale).isEqualTo(CellScale.UNIT);
        assertThat(inferred.currency).isEqualTo("INR");
        assertThat(inferred.typeSource).isEqualTo("derived");
    }

    @Test
    void totalRowDoesNotInferOnMismatchedNeighbors() {
        // Row 5 has "Total" label
        // B3 is money, B7 is quantity (conflict)
        // B5 should NOT be inferred (neighbors disagree)
        List<InterpretationCellView> cells = new ArrayList<>();
        cells.add(cell(1, 5, 1, "Total", "text"));
        cells.add(cell(2, 3, 2, "100", "number", "100"));
        cells.add(cell(3, 5, 2, "?", "number", null)); // untypable
        cells.add(cell(4, 7, 2, "200", "number", "200"));

        Map<Long, ReadingOutcome> settled = new HashMap<>();
        settled.put(2L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(3L, ReadingOutcome.refused("untypable"));
        settled.put(4L, ReadingOutcome.typed("quantity", CellScale.UNIT, "sqm", "INR", "input"));

        new CellReadingInferencer(cells).infer(settled);

        ReadingOutcome notInferred = settled.get(3L);
        assertThat(notInferred.refusal).isEqualTo("untypable");
    }

    @Test
    void totalRowDoesNotInferWithoutNeighbors() {
        // Row 5 has "Total" label but no typed neighbors in B5's column
        // B5 should remain untypable
        List<InterpretationCellView> cells = new ArrayList<>();
        cells.add(cell(1, 5, 1, "Total", "text"));
        cells.add(cell(2, 5, 2, "?", "number", null)); // no neighbors

        Map<Long, ReadingOutcome> settled = new HashMap<>();
        settled.put(2L, ReadingOutcome.refused("untypable"));

        new CellReadingInferencer(cells).infer(settled);

        ReadingOutcome notInferred = settled.get(2L);
        assertThat(notInferred.refusal).isEqualTo("untypable");
    }

    @Test
    void totalLabelInColumnGDetectedByExpandedRange() {
        // "Total" label in column G (not A-E)
        // J28 is untypable, J32 and J34 are typed money
        // Result: J28 should be inferred
        List<InterpretationCellView> cells = new ArrayList<>();
        cells.add(cell(1, 28, 7, "Total", "text")); // Column G
        cells.add(cell(2, 28, 10, "?", "number", null)); // J28 untypable
        cells.add(cell(3, 32, 10, "400", "number", "400")); // J32 typed
        cells.add(cell(4, 34, 10, "2700", "number", "2700")); // J34 typed

        Map<Long, ReadingOutcome> settled = new HashMap<>();
        settled.put(2L, ReadingOutcome.refused("untypable"));
        settled.put(3L, ReadingOutcome.typed("money", CellScale.LAKH, "", "INR", "input"));
        settled.put(4L, ReadingOutcome.typed("money", CellScale.LAKH, "", "INR", "input"));

        new CellReadingInferencer(cells).infer(settled);

        ReadingOutcome inferred = settled.get(2L);
        assertThat(inferred.typed()).isTrue();
        assertThat(inferred.kind).isEqualTo("money");
        assertThat(inferred.scale).isEqualTo(CellScale.LAKH);
    }

    @Test
    void columnConsensusInfersFromMajority() {
        // Column B: 8 cells typed as money, 2 as untypable
        // 8/10 = 80% typed, 8/8 = 100% agree on money
        // Result: both untypable cells should be inferred as money
        List<InterpretationCellView> cells = new ArrayList<>();
        for (int row = 1; row <= 10; row++) {
            cells.add(cell(row, row, 2, String.valueOf(row * 100), "number", String.valueOf(row * 100)));
        }

        Map<Long, ReadingOutcome> settled = new HashMap<>();
        for (int row = 1; row <= 8; row++) {
            settled.put((long) row, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        }
        settled.put(9L, ReadingOutcome.refused("untypable"));
        settled.put(10L, ReadingOutcome.refused("untypable"));

        new CellReadingInferencer(cells).infer(settled);

        assertThat(settled.get(9L).typed()).isTrue();
        assertThat(settled.get(9L).kind).isEqualTo("money");
        assertThat(settled.get(9L).typeSource).isEqualTo("derived");

        assertThat(settled.get(10L).typed()).isTrue();
        assertThat(settled.get(10L).kind).isEqualTo("money");
        assertThat(settled.get(10L).typeSource).isEqualTo("derived");
    }

    @Test
    void columnConsensusRequires70PercentAgreement() {
        // Column B: 10 cells, 7 typed
        // 7/10 = 70% typed ✓
        // 5 money + 2 quantity
        // 5/7 = 71% agree on money ✓
        // Result: both untypable cells should be inferred as money
        List<InterpretationCellView> cells = new ArrayList<>();
        for (int row = 1; row <= 10; row++) {
            cells.add(cell(row, row, 2, String.valueOf(row), "number", String.valueOf(row)));
        }

        Map<Long, ReadingOutcome> settled = new HashMap<>();
        settled.put(1L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(2L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(3L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(4L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(5L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(6L, ReadingOutcome.typed("quantity", CellScale.UNIT, "sqm", "INR", "input"));
        settled.put(7L, ReadingOutcome.typed("quantity", CellScale.UNIT, "sqm", "INR", "input"));
        settled.put(8L, ReadingOutcome.refused("untypable"));
        settled.put(9L, ReadingOutcome.refused("untypable"));
        settled.put(10L, ReadingOutcome.refused("untypable"));

        new CellReadingInferencer(cells).infer(settled);

        // 5/7 = 71% agree on money, exceeds 70% threshold
        assertThat(settled.get(8L).typed()).isTrue();
        assertThat(settled.get(8L).kind).isEqualTo("money");
        assertThat(settled.get(9L).typed()).isTrue();
        assertThat(settled.get(9L).kind).isEqualTo("money");
        assertThat(settled.get(10L).typed()).isTrue();
        assertThat(settled.get(10L).kind).isEqualTo("money");
    }

    @Test
    void columnConsensusIgnoresColumnsBelow50PercentTyped() {
        // Column B: 10 cells, 4 typed (40%)
        // 40% < 50% threshold
        // Result: untypable cells should NOT be inferred
        List<InterpretationCellView> cells = new ArrayList<>();
        for (int row = 1; row <= 10; row++) {
            cells.add(cell(row, row, 2, String.valueOf(row), "number", String.valueOf(row)));
        }

        Map<Long, ReadingOutcome> settled = new HashMap<>();
        settled.put(1L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(2L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(3L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(4L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        for (int row = 5; row <= 10; row++) {
            settled.put((long) row, ReadingOutcome.refused("untypable"));
        }

        new CellReadingInferencer(cells).infer(settled);

        assertThat(settled.get(5L).refusal).isEqualTo("untypable");
        assertThat(settled.get(10L).refusal).isEqualTo("untypable");
    }

    @Test
    void columnConsensusIgnoresNonNumericCells() {
        // Column B: 8 numeric cells typed, 2 text cells untypable
        // Text cells should NOT be inferred even with 80% consensus
        List<InterpretationCellView> cells = new ArrayList<>();
        for (int row = 1; row <= 8; row++) {
            cells.add(cell(row, row, 2, String.valueOf(row * 100), "number", String.valueOf(row * 100)));
        }
        cells.add(cell(9, 9, 2, "text", "text"));
        cells.add(cell(10, 10, 2, "text", "text"));

        Map<Long, ReadingOutcome> settled = new HashMap<>();
        for (int row = 1; row <= 8; row++) {
            settled.put((long) row, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        }
        settled.put(9L, ReadingOutcome.refused("untypable"));
        settled.put(10L, ReadingOutcome.refused("untypable"));

        new CellReadingInferencer(cells).infer(settled);

        assertThat(settled.get(9L).refusal).isEqualTo("untypable");
        assertThat(settled.get(10L).refusal).isEqualTo("untypable");
    }

    @Test
    void inferredOutcomesHaveDerivedTypeSource() {
        // Both inference rules should mark inferred types with typeSource="derived"
        List<InterpretationCellView> cells = new ArrayList<>();
        cells.add(cell(1, 5, 1, "Total", "text"));
        cells.add(cell(2, 3, 2, "100", "number", "100"));
        cells.add(cell(3, 5, 2, "?", "number", null));
        cells.add(cell(4, 7, 2, "200", "number", "200"));

        Map<Long, ReadingOutcome> settled = new HashMap<>();
        settled.put(2L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(3L, ReadingOutcome.refused("untypable"));
        settled.put(4L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));

        new CellReadingInferencer(cells).infer(settled);

        assertThat(settled.get(3L).typeSource).isEqualTo("derived");
    }

    @Test
    void dontInferAlreadyTypedCells() {
        // Already typed cells should not be overwritten
        List<InterpretationCellView> cells = new ArrayList<>();
        cells.add(cell(1, 5, 1, "Total", "text"));
        cells.add(cell(2, 3, 2, "100", "number", "100"));
        cells.add(cell(3, 5, 2, "?", "number", null));
        cells.add(cell(4, 7, 2, "200", "number", "200"));

        Map<Long, ReadingOutcome> settled = new HashMap<>();
        settled.put(2L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        settled.put(3L, ReadingOutcome.typed("quantity", CellScale.UNIT, "sqm", "INR", "input")); // Already typed
        settled.put(4L, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));

        new CellReadingInferencer(cells).infer(settled);

        // Should remain as quantity, not be changed to money
        assertThat(settled.get(3L).kind).isEqualTo("quantity");
        assertThat(settled.get(3L).typeSource).isEqualTo("input");
    }

    @Test
    void multipleInferenceRulesCanApplySequentially() {
        // Column B has 10 cells, rows 1-10
        // Cell IDs: 100-109
        // Rows 1-8: typed as money (80% density)
        // Rows 9-10: untypable (should be inferred by column consensus)
        List<InterpretationCellView> cells = new ArrayList<>();
        for (int row = 1; row <= 10; row++) {
            cells.add(cell(100 + row, row, 2, String.valueOf(row * 100), "number", String.valueOf(row * 100)));
        }

        Map<Long, ReadingOutcome> settled = new HashMap<>();
        for (int row = 1; row <= 8; row++) {
            settled.put(100L + row, ReadingOutcome.typed("money", CellScale.UNIT, "", "INR", "input"));
        }
        settled.put(109L, ReadingOutcome.refused("untypable"));
        settled.put(110L, ReadingOutcome.refused("untypable"));

        new CellReadingInferencer(cells).infer(settled);

        // Column consensus should infer both untypable cells (80% typed, 100% money)
        assertThat(settled.get(109L).typed()).isTrue();
        assertThat(settled.get(109L).kind).isEqualTo("money");
        assertThat(settled.get(110L).typed()).isTrue();
        assertThat(settled.get(110L).kind).isEqualTo("money");
    }

    // Helper methods
    private InterpretationCellView cell(
            long id, int row, int col, String textValue, String valueType) {
        return new InterpretationCellView(
                id,
                1L, // worksheetId
                String.format("%s%d", colName(col), row), // coord
                row,
                col,
                valueType,
                textValue,
                textValue,
                null, // numericValue
                null, // boolValue
                null, // dateValue
                null, // formulaText
                null, // formulaState
                null, // cachedValue
                null, // cacheState
                false, // isError
                null, // errorType
                false, // isMergedAnchor
                false, // isMergedParticipant
                null, // mergedRange
                null); // valueSource
    }

    private InterpretationCellView cell(
            long id, int row, int col, String textValue, String valueType, String numericValue) {
        return new InterpretationCellView(
                id,
                1L, // worksheetId
                String.format("%s%d", colName(col), row), // coord
                row,
                col,
                valueType,
                textValue,
                textValue,
                numericValue,
                null, // boolValue
                null, // dateValue
                null, // formulaText
                null, // formulaState
                null, // cachedValue
                null, // cacheState
                false, // isError
                null, // errorType
                false, // isMergedAnchor
                false, // isMergedParticipant
                null, // mergedRange
                null); // valueSource
    }

    private String colName(int col) {
        if (col <= 26) {
            return String.valueOf((char) ('A' + col - 1));
        }
        return "?";
    }
}
