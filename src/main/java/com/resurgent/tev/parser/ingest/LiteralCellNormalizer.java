package com.resurgent.tev.parser.ingest;

import org.apache.poi.ss.formula.eval.ErrorEval;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;

/**
 * Normalizes non-formula cells (numeric, string, boolean, error) into the canonical
 * cell contract. Shared by the XLSX and XLS adapters.
 */
final class LiteralCellNormalizer {

    private LiteralCellNormalizer() {
    }

    static NormalizedCell normalizeLiteralCell(Cell cell, String coord,
            int rowNum, int colNum, boolean rowHidden, boolean colHidden, boolean sheetHidden) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.BLANK) {
            return null;
        }

        String rawValue;
        CellValue value;

        switch (type) {
            case NUMERIC -> {
                rawValue = Double.toString(cell.getNumericCellValue());
                if (DateUtil.isCellDateFormatted(cell)) {
                    value = CellNormalizer.normalizeDate(cell.getLocalDateTimeCellValue());
                } else {
                    value = numericValue(rawValue);
                }
            }
            case STRING -> {
                rawValue = cell.getStringCellValue();
                value = CellNormalizer.normalize(rawValue);
            }
            case BOOLEAN -> {
                rawValue = cell.getBooleanCellValue() ? "TRUE" : "FALSE";
                value = CellNormalizer.normalize(rawValue);
            }
            case ERROR -> {
                rawValue = errorLiteral(cell.getErrorCellValue());
                value = CellNormalizer.normalize(rawValue);
            }
            default -> {
                return null;
            }
        }

        return NormalizedCellFactory.buildCell(coord, rowNum, colNum, rawValue, value,
                null, null, null, null, null,
                rowHidden, colHidden, sheetHidden);
    }

    static String errorLiteral(byte errorCode) {
        return ErrorEval.getText(errorCode);
    }

    static CellValue numericValue(String rawValue) {
        return new CellValue(
                "number", "number", rawValue, rawValue,
                new java.math.BigDecimal(rawValue), null, null,
                false, null, false, null);
    }
}
