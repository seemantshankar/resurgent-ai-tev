package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Post-processor that infers types for untypable cells based on structural context.
 * 
 * Rules:
 * 1. Total rows: If a row contains "Total" label and neighbors in the same column
 *    are typed (money/quantity/etc), infer the same type.
 * 2. Column consensus: If 70%+ of typed cells in a column share the same kind+scale,
 *    infer untypable cells in that column as the same.
 */
public final class CellReadingInferencer {
    private final Map<Integer, List<InterpretationCellView>> cellsByRow;
    private final Map<Integer, List<InterpretationCellView>> cellsByCol;

    public CellReadingInferencer(List<InterpretationCellView> cells) {
        this.cellsByRow = cells.stream()
                .collect(Collectors.groupingByConcurrent(InterpretationCellView::rowNum));
        this.cellsByCol = cells.stream()
                .collect(Collectors.groupingByConcurrent(InterpretationCellView::colNum));
    }

    /**
     * Infer types for untypable cells using structural context.
     * Modifies the settled map in-place, replacing UNTYPABLE outcomes with inferred ones.
     */
    public void infer(Map<Long, ReadingOutcome> settled) {
        // Rule 1: Total rows - if row is labeled "Total" and has typed neighbors, infer from them
        for (List<InterpretationCellView> rowCells : cellsByRow.values()) {
            if (isLabeledAsTotal(rowCells)) {
                inferTotalRow(rowCells, settled);
            }
        }

        // Rule 2: Column consensus - if column has strong type majority, infer untypable cells
        for (List<InterpretationCellView> colCells : cellsByCol.values()) {
            inferColumnConsensus(colCells, settled);
        }
    }

    private boolean isLabeledAsTotal(List<InterpretationCellView> rowCells) {
        return rowCells.stream()
                .filter(c -> c.colNum() <= 10)  // Check columns A-J for labels
                .anyMatch(c -> {
                    String text = c.textValue();
                    if (text == null) return false;
                    String lower = text.toLowerCase();
                    return lower.contains("total") || lower.contains("subtotal") || lower.contains("sum");
                });
    }

    private void inferTotalRow(List<InterpretationCellView> rowCells, Map<Long, ReadingOutcome> settled) {
        for (InterpretationCellView cell : rowCells) {
            long cellId = cell.cellId();
            ReadingOutcome outcome = settled.get(cellId);

            if (outcome != null && outcome.typed()) {
                continue;
            }

            if (!isNumeric(cell)) {
                continue;
            }

            if (outcome == null || !ReadingOutcome.UNTYPABLE.equals(outcome.refusal)) {
                continue;
            }

            ReadingOutcome inferred = inferFromColumnNeighbors(cell, settled);
            if (inferred != null) {
                settled.put(cellId, inferred);
            }
        }
    }

    private ReadingOutcome inferFromColumnNeighbors(InterpretationCellView untypable, Map<Long, ReadingOutcome> settled) {
        int col = untypable.colNum();
        int row = untypable.rowNum();
        int range = 5;

        List<ReadingOutcome> typedOutcomes = cellsByCol.getOrDefault(col, List.of()).stream()
                .filter(c -> Math.abs(c.rowNum() - row) <= range && c.rowNum() != row)
                .map(c -> settled.get(c.cellId()))
                .filter(o -> o != null && o.typed())
                .collect(Collectors.toList());

        if (typedOutcomes.isEmpty()) {
            return null;
        }

        ReadingOutcome first = typedOutcomes.get(0);
        boolean allAgree = typedOutcomes.stream()
                .allMatch(o -> first.kind.equals(o.kind) && 
                               (first.scale == null ? o.scale == null : first.scale.equals(o.scale)) &&
                               first.currency.equals(o.currency));

        if (allAgree) {
            return ReadingOutcome.typed(
                    first.kind, first.scale, first.unit, first.currency, ReadingOutcome.DERIVED);
        }

        return null;
    }

    private void inferColumnConsensus(List<InterpretationCellView> colCells, Map<Long, ReadingOutcome> settled) {
        long typedCount = colCells.stream()
                .map(c -> settled.get(c.cellId()))
                .filter(o -> o != null && o.typed())
                .count();

        if (typedCount == 0) {
            return;
        }

        long untypableCount = colCells.stream()
                .map(c -> settled.get(c.cellId()))
                .filter(o -> o != null && ReadingOutcome.UNTYPABLE.equals(o.refusal))
                .count();

        if (untypableCount == 0) {
            return;
        }

        double typedRatio = (double) typedCount / colCells.size();
        if (typedRatio < 0.5) {
            return;
        }

        Map<String, Long> typeFreq = new HashMap<>();
        colCells.stream()
                .map(c -> settled.get(c.cellId()))
                .filter(o -> o != null && o.typed())
                .forEach(o -> {
                    String key = o.kind + "|" + (o.scale == null ? "null" : o.scale.wireName()) + "|" + o.currency;
                    typeFreq.merge(key, 1L, Long::sum);
                });

        String dominantType = typeFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (dominantType == null) {
            return;
        }

        String[] parts = dominantType.split("\\|", 3);
        String kind = parts[0];
        String scaleWire = parts[1];
        String currency = parts[2];

        long dominantCount = typeFreq.get(dominantType);
        double dominantRatio = (double) dominantCount / typedCount;

        if (dominantRatio < 0.7) {
            return;
        }

        CellScale scale = "null".equals(scaleWire) ? null : CellScale.fromWire(scaleWire);

        for (InterpretationCellView cell : colCells) {
            long cellId = cell.cellId();
            ReadingOutcome outcome = settled.get(cellId);

            if (outcome != null && ReadingOutcome.UNTYPABLE.equals(outcome.refusal) && isNumeric(cell)) {
                settled.put(cellId, ReadingOutcome.typed(
                        kind, scale, "", currency, ReadingOutcome.DERIVED));
            }
        }
    }

    private boolean isNumeric(InterpretationCellView cell) {
        if (cell.isError()) return false;
        if ("number".equals(cell.valueType())) return true;
        if (cell.formulaText() == null || cell.formulaText().isBlank()) return false;
        return cell.numericValue() != null && !cell.numericValue().isBlank();
    }
}
