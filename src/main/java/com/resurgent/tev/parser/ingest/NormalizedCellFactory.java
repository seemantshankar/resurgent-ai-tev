package com.resurgent.tev.parser.ingest;

import org.apache.poi.ss.util.CellRangeAddress;

/**
 * Factory for building {@link NormalizedCell} instances and applying merged-region
 * anchor/participant semantics. Shared between the XLSX and XLS adapters.
 */
final class NormalizedCellFactory {

    private NormalizedCellFactory() {
    }

    static NormalizedCell buildCell(String coord, int rowNum, int colNum,
            String rawValue, CellValue value, String formulaText,
            String formulaState, String cachedValue, String cacheState,
            boolean rowHidden, boolean colHidden, boolean sheetHidden) {
        return new NormalizedCell(
                coord, rowNum, colNum,
                rawValue,
                value.rawType(),
                value.valueType(),
                value.textValue(),
                value.displayValue(),
                value.numericValue(),
                value.boolValue(),
                value.dateValue(),
                formulaText,
                formulaState,
                cachedValue,
                cacheState,
                value.coercedFromText(),
                value.isError(),
                value.errorType(),
                false, false, null, "cell",
                rowHidden, colHidden, sheetHidden);
    }

    static NormalizedCell markAnchor(NormalizedCell cell, CellRangeAddress region) {
        return new NormalizedCell(
                cell.coord(), cell.rowNum(), cell.colNum(),
                cell.rawValue(),
                cell.rawType(),
                cell.valueType(),
                cell.textValue(),
                cell.displayValue(),
                cell.numericValue(),
                cell.boolValue(),
                cell.dateValue(),
                cell.formulaText(),
                cell.formulaState(),
                cell.cachedValue(),
                cell.cacheState(),
                cell.coercedFromText(),
                cell.isError(),
                cell.errorType(),
                true, false, region.formatAsString(), "cell",
                cell.rowHidden(), cell.colHidden(), cell.sheetHidden());
    }

    static NormalizedCell createParticipant(NormalizedCell anchor, CellRangeAddress region,
            int rowNum, int colNum, String coord,
            boolean rowHidden, boolean colHidden, boolean sheetHidden) {
        String displayValue = anchor == null ? null : anchor.displayValue();
        return new NormalizedCell(
                coord, rowNum, colNum,
                null,
                "empty",
                "empty",
                null,
                displayValue,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                false, true, region.formatAsString(), "merged_anchor",
                rowHidden, colHidden, sheetHidden);
    }
}
