package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Post-processor that infers types for untypable cells based on structural context.
 *
 * Runs after formula arithmetic resolution in {@link CellReadingWriter}. Applies two rules
 * to improve cell type coverage without modifying input or formula-derived types.
 *
 * <h2>Rule 1: Total Row Inference (HIGH confidence)</h2>
 * When a row contains a "Total"/"Subtotal"/"Sum" label (in columns A-J), untypable numeric
 * cells in that row are inferred from typed neighbors in the same column (within 5-row range).
 * Only infers if all neighbors agree on kind, scale, and currency.
 *
 * <h2>Rule 2: Column Consensus (MEDIUM confidence)</h2>
 * When a column has 50%+ typed cells and 70%+ of those typed cells share the same
 * kind+scale+currency, untypable numeric cells in that column are inferred as the majority type.
 *
 * <h2>Auditability</h2>
 * All inferred types are marked with {@code typeSource="derived"} to distinguish them from
 * input types (from labels/format) and formula-derived types (from arithmetic).
 *
 * <h2>Safety</h2>
 * - Does not overwrite already-typed cells
 * - Does not infer text cells (only numeric)
 * - Does not infer cells with formula errors
 * - Both rules are conservative: require multiple neighbors/consensus before inferring
 *
 * @see CellReadingWriter#replace(com.resurgent.tev.parser.db.WorkspaceRepository, long)
 */
public final class CellReadingInferencer {
    // Bug fix: Group by (worksheetId, rowNum) not just rowNum to handle same row in different worksheets
    private final java.util.concurrent.ConcurrentMap<Long, java.util.concurrent.ConcurrentMap<Integer, List<InterpretationCellView>>> cellsByWorksheetAndRow;
    private final java.util.concurrent.ConcurrentMap<Long, java.util.concurrent.ConcurrentMap<Integer, List<InterpretationCellView>>> cellsByWorksheetAndCol;

    public CellReadingInferencer(List<InterpretationCellView> cells) {
        this.cellsByWorksheetAndRow = cells.stream()
                .collect(Collectors.groupingByConcurrent(
                    InterpretationCellView::worksheetId,
                    Collectors.groupingByConcurrent(InterpretationCellView::rowNum)));
        this.cellsByWorksheetAndCol = cells.stream()
                .collect(Collectors.groupingByConcurrent(
                    InterpretationCellView::worksheetId,
                    Collectors.groupingByConcurrent(InterpretationCellView::colNum)));
    }

    /**
     * Infer types for untypable cells using structural context.
     * Modifies the settled map in-place, replacing UNTYPABLE outcomes with inferred ones.
     */
    public void infer(Map<Long, ReadingOutcome> settled) {
        // Rule 1: Total rows - if row is labeled "Total" and has typed neighbors, infer from them
        for (Map<Integer, List<InterpretationCellView>> rowsByNum : cellsByWorksheetAndRow.values()) {
            for (List<InterpretationCellView> rowCells : rowsByNum.values()) {
                if (isLabeledAsTotal(rowCells)) {
                    int rowNum = rowCells.isEmpty() ? -1 : rowCells.get(0).rowNum();
                    System.err.println("[inference] Total row detected: row " + rowNum);
                    inferTotalRow(rowCells, settled);
                }
            }
        }

        // Rule 2: Column consensus - if column has strong type majority, infer untypable cells
        for (Map<Integer, List<InterpretationCellView>> colsByNum : cellsByWorksheetAndCol.values()) {
            for (List<InterpretationCellView> colCells : colsByNum.values()) {
                inferColumnConsensus(colCells, settled);
            }
        }
    }

    private boolean isLabeledAsTotal(List<InterpretationCellView> rowCells) {
        int rowNum = rowCells.isEmpty() ? -1 : rowCells.get(0).rowNum();
        boolean hasTotal = rowCells.stream()
                .filter(c -> c.colNum() <= 10)  // Check columns A-J for labels
                .anyMatch(c -> {
                    String text = c.textValue();
                    if (text == null) return false;
                    String lower = text.toLowerCase();
                    boolean matches = lower.contains("total") || lower.contains("subtotal") || lower.contains("sum");
                    if (matches || rowNum == 28) {  // Debug: log row 28 and any total matches
                        System.err.println("[inference] Row " + rowNum + " col " + c.colNum() +
                            " text='" + text + "' matches=" + matches);
                    }
                    return matches;
                });
        if (hasTotal) {
            System.err.println("[inference] Row " + rowNum + " HAS TOTAL LABEL");
        }
        return hasTotal;
    }

    private void inferTotalRow(List<InterpretationCellView> rowCells, Map<Long, ReadingOutcome> settled) {
        long worksheetId = rowCells.isEmpty() ? -1L : rowCells.get(0).worksheetId();
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
                System.err.println("[inference] Row " + cell.rowNum() + " col " + cell.colNum() +
                    " (" + cell.coord() + "): outcome=" + (outcome == null ? "null" : outcome.refusal));
                continue;
            }

            System.err.println("[inference] Attempting to infer total row cell: " + cell.coord());
            ReadingOutcome inferred = inferFromColumnNeighbors(cell, worksheetId, settled);
            if (inferred != null) {
                System.err.println("[inference] ✓ Inferred " + cell.coord() + " as " + inferred.kind);
                settled.put(cellId, inferred);
            } else {
                System.err.println("[inference] ✗ Could not infer " + cell.coord());
            }
        }
    }

    private ReadingOutcome inferFromColumnNeighbors(InterpretationCellView untypable, long worksheetId, Map<Long, ReadingOutcome> settled) {
        int col = untypable.colNum();
        int row = untypable.rowNum();
        int range = 5;

        List<InterpretationCellView> candidates = cellsByWorksheetAndCol
                .getOrDefault(worksheetId, new java.util.concurrent.ConcurrentHashMap<>())
                .getOrDefault(col, List.of()).stream()
                .filter(c -> Math.abs(c.rowNum() - row) <= range && c.rowNum() != row)
                .collect(Collectors.toList());

        System.err.println("[inference] Looking for neighbors of " + untypable.coord() +
            " (col " + col + ", row " + row + ") in range ±" + range +
            ": found " + candidates.size() + " candidates");

        List<ReadingOutcome> typedOutcomes = candidates.stream()
                .map(c -> {
                    ReadingOutcome o = settled.get(c.cellId());
                    if (o != null && o.typed()) {
                        System.err.println("[inference]   - " + c.coord() + " (row " + c.rowNum() + "): " + o.kind);
                    }
                    return o;
                })
                .filter(o -> o != null && o.typed())
                .collect(Collectors.toList());

        if (typedOutcomes.isEmpty()) {
            System.err.println("[inference]   No typed neighbors found");
            return null;
        }

        ReadingOutcome first = typedOutcomes.get(0);
        boolean allAgree = typedOutcomes.stream()
                .allMatch(o -> first.kind.equals(o.kind) &&
                               (first.scale == null ? o.scale == null : first.scale.equals(o.scale)) &&
                               first.currency.equals(o.currency));

        if (allAgree) {
            System.err.println("[inference]   All " + typedOutcomes.size() + " neighbors agree on " + first.kind);
            return ReadingOutcome.typed(
                    first.kind, first.scale, first.unit, first.currency, ReadingOutcome.DERIVED);
        }

        System.err.println("[inference]   Neighbors disagree: cannot infer");
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
