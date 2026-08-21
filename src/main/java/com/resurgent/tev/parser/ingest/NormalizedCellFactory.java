package com.resurgent.tev.parser.ingest;

import org.apache.poi.ss.util.CellRangeAddress;

/**
 * Factory for building {@link NormalizedCell} instances and applying merged-region
 * anchor/participant semantics. Shared between the XLSX and XLS adapters so both
 * formats emit the same structural contract.
 */
final class NormalizedCellFactory {

    private NormalizedCellFactory() {
    }

    static NormalizedCell buildCell(String coord, int rowNum, int colNum,
            String rawValue, CellValue value, String formulaText,
            String formulaNormalized, String formulaState, String cachedValue,
            String cacheState, boolean rowHidden, boolean colHidden, boolean sheetHidden,
            String externalRef, String sheetRefs, String definedNameRefs) {
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
                value.parsedQuantity(),
                value.isError(),
                value.errorType(),
                null, null,
                false, false, null, "cell",
                rowHidden, colHidden, sheetHidden,
                externalRef, null, sheetRefs, definedNameRefs);
    }

    static NormalizedCell markAnchor(NormalizedCell cell, CellRangeAddress region) {
        String range = region.formatAsString();
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
                cell.parsedQuantity(),
                cell.isError(),
                cell.errorType(),
                cell.rowLabel(),
                cell.colLabel(),
                true, false, range, "cell",
                cell.rowHidden(), cell.colHidden(), cell.sheetHidden(),
                cell.externalRef(), cell.externalLinkId(), cell.sheetRefs(), cell.definedNameRefs());
    }

    static NormalizedCell createParticipant(NormalizedCell anchor, CellRangeAddress region,
            int rowNum, int colNum, String coord,
            boolean rowHidden, boolean colHidden, boolean sheetHidden) {
        String displayValue = anchor == null ? null : anchor.displayValue();
        String range = region.formatAsString();
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
                null,
                false,
                null,
                null,
                null,
                false, true, range, "merged_anchor",
                rowHidden, colHidden, sheetHidden,
                null, null, null, null);
    }
}
