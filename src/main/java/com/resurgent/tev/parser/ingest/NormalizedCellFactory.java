package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.db.CellStyle;
import org.apache.poi.ss.util.CellRangeAddress;

/**
 * Factory for building {@link NormalizedCell} instances and applying merged-region
 * anchor/participant semantics. Shared between the XLSX and XLS adapters.
 */
final class NormalizedCellFactory {

    private NormalizedCellFactory() {
    }

    static NormalizedCell buildCell(String coord, int rowNum, int colNum,
            String rawValue, CellValue value, String formulaText, String formulaNormalized,
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
                formulaNormalized,
                formulaState,
                cachedValue,
                cacheState,
                value.coercedFromText(),
                value.isError(),
                value.errorType(),
                false, false, null, "cell",
                rowHidden, colHidden, sheetHidden,
                null);
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
                cell.formulaNormalized(),
                cell.formulaState(),
                cell.cachedValue(),
                cell.cacheState(),
                cell.coercedFromText(),
                cell.isError(),
                cell.errorType(),
                true, false, region.formatAsString(), "cell",
                cell.rowHidden(), cell.colHidden(), cell.sheetHidden(),
                cell.cellStyle());
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
                null,
                false,
                false,
                null,
                false, true, region.formatAsString(), "merged_anchor",
                rowHidden, colHidden, sheetHidden,
                null);
    }

    static NormalizedCell attachStyle(NormalizedCell cell, CellStyle style) {
        return cell == null ? null : cell.withCellStyle(style);
    }

    static NormalizedCell buildStyledBlank(String coord, int rowNum, int colNum,
            boolean rowHidden, boolean colHidden, boolean sheetHidden, CellStyle style) {
        CellValue empty = CellValue.empty();
        return new NormalizedCell(
                coord, rowNum, colNum,
                null,
                empty.rawType(),
                empty.valueType(),
                empty.textValue(),
                empty.displayValue(),
                empty.numericValue(),
                empty.boolValue(),
                empty.dateValue(),
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                false, false, null, "cell",
                rowHidden, colHidden, sheetHidden,
                style);
    }
}
