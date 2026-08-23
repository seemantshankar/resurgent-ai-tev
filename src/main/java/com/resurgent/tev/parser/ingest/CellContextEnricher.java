package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Enriches a sheet's normalized cells with denormalized row and column header
 * labels. The labels are inferred heuristically from the sheet itself so that
 * downstream queries can ask "PBIT for Year 5" without joining to other cells.
 *
 * <p>Row label: leftmost non-empty text cell in the same row (typically column
 * A). Column label: text from the detected header row in the same column. Header
 * row detection prefers the row with the most "YearN" / "Year N" labels; if none
 * is found, it falls back to the topmost text cell in the column above each cell.
 */
public final class CellContextEnricher {

    private static final Pattern YEAR_HEADER = Pattern.compile("^\\s*Year\\s*\\d+\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Returns a new list of cells with {@code rowLabel} and {@code colLabel}
     * populated. The input list is not modified.
     */
    public List<NormalizedCell> enrich(List<NormalizedCell> cells) {
        SheetIndex index = new SheetIndex(cells);
        int headerRow = detectYearHeaderRow(index);

        List<NormalizedCell> enriched = new ArrayList<>(cells.size());
        for (NormalizedCell cell : cells) {
            String rowLabel = findRowLabel(cell, index);
            String colLabel = findColLabel(cell, index, headerRow);
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
                    cell.numberFormat()));
        }
        return enriched;
    }

    private String findRowLabel(NormalizedCell cell, SheetIndex index) {
        int row = cell.rowNum();
        int col = 1;
        while (col <= index.maxCol(row)) {
            NormalizedCell candidate = index.get(row, col);
            if (candidate != null && isText(candidate)) {
                return candidate.displayValue();
            }
            col++;
        }
        return null;
    }

    private String findColLabel(NormalizedCell cell, SheetIndex index, int headerRow) {
        int col = cell.colNum();
        if (headerRow > 0) {
            NormalizedCell header = index.get(headerRow, col);
            if (header != null && isText(header)) {
                return header.displayValue();
            }
        }
        // Fallback: topmost text cell in the same column above this cell.
        for (int row = 1; row < cell.rowNum(); row++) {
            NormalizedCell candidate = index.get(row, col);
            if (candidate != null && isText(candidate)) {
                return candidate.displayValue();
            }
        }
        return null;
    }

    private int detectYearHeaderRow(SheetIndex index) {
        int bestRow = -1;
        int bestCount = 0;
        for (int row = 1; row <= index.maxRow(); row++) {
            int count = 0;
            for (int col = 1; col <= index.maxCol(row); col++) {
                NormalizedCell cell = index.get(row, col);
                if (cell != null && isText(cell)
                        && YEAR_HEADER.matcher(cell.displayValue()).matches()) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestRow = row;
            }
        }
        return bestRow;
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
        private int maxRow = 0;

        SheetIndex(List<NormalizedCell> cells) {
            for (NormalizedCell cell : cells) {
                byRow.computeIfAbsent(cell.rowNum(), r -> new HashMap<>())
                        .put(cell.colNum(), cell);
                if (cell.rowNum() > maxRow) {
                    maxRow = cell.rowNum();
                }
            }
        }

        NormalizedCell get(int row, int col) {
            Map<Integer, NormalizedCell> cols = byRow.get(row);
            return cols == null ? null : cols.get(col);
        }

        int maxRow() {
            return maxRow;
        }

        int maxCol(int row) {
            Map<Integer, NormalizedCell> cols = byRow.get(row);
            if (cols == null) {
                return 0;
            }
            int max = 0;
            for (int c : cols.keySet()) {
                if (c > max) {
                    max = c;
                }
            }
            return max;
        }
    }
}
