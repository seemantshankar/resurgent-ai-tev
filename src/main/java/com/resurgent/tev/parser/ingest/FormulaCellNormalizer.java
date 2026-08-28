package com.resurgent.tev.parser.ingest;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;

/**
 * Normalizes formula cells into the canonical cell contract. Shared by the XLSX
 * and XLS adapters. The caller decides whether the formula text is available
 * ({@code formulaText != null}) and whether the HSSF/XSSF record carries a
 * cached value; this class builds the matching {@link NormalizedCell}.
 */
final class FormulaCellNormalizer {

    private FormulaCellNormalizer() {
    }

    static NormalizedCell normalizeFormulaCell(String formulaText, Cell valueCell,
            String coord, int rowNum, int colNum,
            boolean rowHidden, boolean colHidden, boolean sheetHidden,
            boolean cacheFresh, boolean hasCachedValue) {
        if (formulaText == null) {
            return normalizeUnavailableFormula(valueCell, coord, rowNum, colNum,
                    rowHidden, colHidden, sheetHidden, cacheFresh, hasCachedValue);
        }

        CachedResult cachedResult = readCachedValue(valueCell, cacheFresh, hasCachedValue);
        CellValue cached = cachedResult.value();
        String cachedValue = cachedResult.raw();
        String cacheState = cachedResult.state();

        String valueType = cached.valueType().equals("empty") ? "formula" : cached.valueType();
        CellValue value = new CellValue(
                "formula", valueType,
                cached.textValue(), cached.displayValue(),
                cached.numericValue(), cached.boolValue(), cached.dateValue(),
                cached.coercedFromText(), cached.isError(), cached.errorType());

        return NormalizedCellFactory.buildCell(coord, rowNum, colNum, "=" + formulaText, value,
                formulaText, "ok", cachedValue, cacheState,
                rowHidden, colHidden, sheetHidden);
    }

    private static NormalizedCell normalizeUnavailableFormula(Cell valueCell,
            String coord, int rowNum, int colNum,
            boolean rowHidden, boolean colHidden, boolean sheetHidden,
            boolean cacheFresh, boolean hasCachedValue) {
        CachedResult cachedResult = readCachedValue(valueCell, cacheFresh, hasCachedValue);
        CellValue cached = cachedResult.value();
        String cachedValue = cachedResult.raw();
        String cacheState = cachedResult.state();

        String valueType = cached.valueType().equals("empty") ? "formula" : cached.valueType();
        CellValue value = new CellValue(
                "formula", valueType,
                cached.textValue(), cached.displayValue(),
                cached.numericValue(), cached.boolValue(), cached.dateValue(),
                cached.coercedFromText(), cached.isError(), cached.errorType());

        return NormalizedCellFactory.buildCell(coord, rowNum, colNum, null, value,
                null, "unavailable", cachedValue, cacheState,
                rowHidden, colHidden, sheetHidden);
    }

    private static CachedResult readCachedValue(Cell valueCell, boolean cacheFresh, boolean hasCachedValue) {
        if (!hasCachedValue) {
            return new CachedResult(null, CellValue.empty(), "missing");
        }

        CellType cachedType = valueCell.getCachedFormulaResultType();
        if (cachedType == CellType.BLANK) {
            return new CachedResult("", CellValue.empty(), cacheFresh ? "fresh" : "stale");
        }

        String cachedValue = cachedValueString(valueCell, cachedType);
        CellValue cached = cachedType == CellType.ERROR
                ? CellNormalizer.normalize(cachedValue)
                : normalizeCachedValue(valueCell, cachedType, cachedValue);
        return new CachedResult(cachedValue, cached, cacheFresh ? "fresh" : "stale");
    }

    private record CachedResult(String raw, CellValue value, String state) {
    }

    private static CellValue normalizeCachedValue(Cell cell, CellType cachedType, String cachedValue) {
        if (cachedValue == null) {
            return CellValue.empty();
        }
        if (cachedType == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return CellNormalizer.normalizeDate(cell.getLocalDateTimeCellValue());
            }
            return LiteralCellNormalizer.numericValue(cachedValue);
        }
        return CellNormalizer.normalize(cachedValue);
    }

    private static String cachedValueString(Cell cell, CellType cachedType) {
        return switch (cachedType) {
            case NUMERIC -> Double.toString(cell.getNumericCellValue());
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            case ERROR -> LiteralCellNormalizer.errorLiteral(cell.getErrorCellValue());
            default -> null;
        };
    }
}
