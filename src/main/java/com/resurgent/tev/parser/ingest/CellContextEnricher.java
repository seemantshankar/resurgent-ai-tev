package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enriches a sheet's normalized cells with denormalized row and column header
 * labels. The labels are inferred heuristically from the sheet itself so that
 * downstream queries can ask "PBIT for Year 5" without joining to other cells.
 *
 * <p>Row label: leading text on the same row, joining a section stub such as
 * {@code (A)} to the description beside it. Column label: the topmost text cell
 * in the same column above each cell. Region-aware header labels replace these
 * provisional values during ingestion.
 */
public final class CellContextEnricher {

    /**
     * Returns a new list of cells with {@code rowLabel} and {@code colLabel}
     * populated. The input list is not modified.
     */
    public List<NormalizedCell> enrich(List<NormalizedCell> cells) {
        SheetIndex index = new SheetIndex(cells);
        List<NormalizedCell> enriched = new ArrayList<>(cells.size());
        for (NormalizedCell cell : cells) {
            String rowLabel = findRowLabel(cell, index);
            String colLabel = findColLabel(cell, index);
            enriched.add(new NormalizedCell(
                    cell.coord(),
                    cell.rowNum(),
                    cell.colNum(),
                    cell.rawValue(),
                    cell.rawType(),
                    cell.valueType(),
                    cell.textValue(),
                    cell.displayValue(),
                    cell.numericValue(),
                    cell.boolValue(),
                    cell.dateValue(),
                    cell.formulaText(),
                    cell.formulaNormalized(),
                    cell.formulaState(),
                    cell.cachedValue(),
                    cell.cacheState(),
                    cell.coercedFromText(),
                    cell.parsedQuantity(),
                    cell.isError(),
                    cell.errorType(),
                    rowLabel,
                    colLabel,
                    cell.isMergedAnchor(),
                    cell.isMergedParticipant(),
                    cell.mergedRange(),
                    cell.valueSource(),
                    cell.rowHidden(),
                    cell.colHidden(),
                    cell.sheetHidden(),
                    cell.isBold(),
                    cell.hasFill(),
                    cell.hasBorder(),
                    cell.numberFormat(),
                    cell.tagsJson()));
        }
        return enriched;
    }

    private String findRowLabel(NormalizedCell cell, SheetIndex index) {
        return RowLabelComposer.compose(index.rowCells(cell.rowNum()));
    }

    private String findColLabel(NormalizedCell cell, SheetIndex index) {
        int col = cell.colNum();
        // Provisional fallback. RegionHeaderAnalyzer replaces this using the full header context.
        for (int row = 1; row < cell.rowNum(); row++) {
            NormalizedCell candidate = index.get(row, col);
            if (candidate != null && isText(candidate)) {
                return candidate.displayValue();
            }
        }
        return null;
    }

    private boolean isText(NormalizedCell cell) {
        return "text".equals(cell.valueType()) && cell.displayValue() != null
                && !cell.displayValue().isBlank();
    }

    /**
     * Simple row/column index over a sheet's cells.
     */
    private static final class SheetIndex {
        private final Map<Integer, Map<Integer, NormalizedCell>> byRow = new HashMap<>();

        SheetIndex(List<NormalizedCell> cells) {
            for (NormalizedCell cell : cells) {
                byRow.computeIfAbsent(cell.rowNum(), r -> new HashMap<>())
                        .put(cell.colNum(), cell);
            }
        }

        NormalizedCell get(int row, int col) {
            Map<Integer, NormalizedCell> cols = byRow.get(row);
            return cols == null ? null : cols.get(col);
        }

        List<NormalizedCell> rowCells(int row) {
            Map<Integer, NormalizedCell> cols = byRow.get(row);
            if (cols == null || cols.isEmpty()) {
                return List.of();
            }
            return List.copyOf(cols.values());
        }
    }
}
