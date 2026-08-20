package com.resurgent.tev.parser.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.formula.eval.ErrorEval;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * XLSX/XLSM adapter. Emulates the openpyxl dual-load pattern required by
 * parser-strategy-v2 §2.1. Apache POI does not expose a {@code data_only} flag,
 * so the adapter opens the workbook twice and conceptually dedicates one
 * instance to formula text ({@code data_only=False}) and the other to cached
 * values ({@code data_only=True}). For each formula cell the formula is read
 * from the formula workbook and the cached value is read from the value
 * workbook.
 *
 * <p>Every sheet, including hidden and veryHidden sheets, is emitted as an
 * {@link XlsxSheet} with normalized canonical cells.
 */
public final class XlsxAdapter {

    public List<XlsxSheet> parse(Path xlsx) throws IOException {
        byte[] bytes = Files.readAllBytes(xlsx);
        try (Workbook formulaBook = open(bytes);
                Workbook valueBook = open(bytes)) {
            boolean cacheFresh = isCacheFresh(valueBook);
            int sheetCount = formulaBook.getNumberOfSheets();
            List<XlsxSheet> sheets = new ArrayList<>(sheetCount);
            for (int i = 0; i < sheetCount; i++) {
                Sheet formulaSheet = formulaBook.getSheetAt(i);
                Sheet valueSheet = valueBook.getSheetAt(i);
                String state = sheetState(formulaBook, i);
                sheets.add(parseSheet(formulaSheet, valueSheet, i, state, cacheFresh));
            }
            return sheets;
        }
    }

    private static boolean isCacheFresh(Workbook workbook) {
        if (workbook.getForceFormulaRecalculation()) {
            return false;
        }
        if (workbook instanceof XSSFWorkbook xssf) {
            var calcPr = xssf.getCTWorkbook().getCalcPr();
            if (calcPr != null && calcPr.getCalcMode() != null) {
                String mode = calcPr.getCalcMode().toString();
                return !"manual".equalsIgnoreCase(mode);
            }
        }
        return true;
    }

    private static Workbook open(byte[] bytes) throws IOException {
        return WorkbookFactory.create(new ByteArrayInputStream(bytes));
    }

    private static String sheetState(Workbook workbook, int index) {
        if (workbook.isSheetVeryHidden(index)) {
            return "veryHidden";
        }
        if (workbook.isSheetHidden(index)) {
            return "hidden";
        }
        return "visible";
    }

    private static XlsxSheet parseSheet(Sheet formulaSheet, Sheet valueSheet,
            int index, String state, boolean cacheFresh) {
        List<NormalizedCell> cells = new ArrayList<>();
        for (Row formulaRow : formulaSheet) {
            if (formulaRow == null) {
                continue;
            }
            Row valueRow = valueSheet.getRow(formulaRow.getRowNum());
            for (Cell formulaCell : formulaRow) {
                if (formulaCell == null) {
                    continue;
                }
                Cell valueCell = valueRow == null
                        ? null
                        : valueRow.getCell(formulaCell.getColumnIndex());
                NormalizedCell normalized = normalizeCell(formulaCell, valueCell, cacheFresh);
                if (normalized != null) {
                    cells.add(normalized);
                }
            }
        }
        return new XlsxSheet(formulaSheet.getSheetName(), index, state, cells);
    }

    private static NormalizedCell normalizeCell(Cell formulaCell, Cell valueCell, boolean cacheFresh) {
        int rowNum = formulaCell.getRowIndex() + 1;
        int colNum = formulaCell.getColumnIndex() + 1;
        String coord = CellReference.convertNumToColString(formulaCell.getColumnIndex()) + rowNum;

        CellType formulaType = formulaCell.getCellType();
        if (formulaType == CellType.BLANK) {
            return null;
        }

        if (formulaType == CellType.FORMULA) {
            return normalizeFormulaCell(formulaCell, valueCell, coord, rowNum, colNum, cacheFresh);
        }

        return normalizeLiteralCell(valueCell, coord, rowNum, colNum);
    }

    private static NormalizedCell normalizeLiteralCell(Cell cell, String coord,
            int rowNum, int colNum) {
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

        return buildCell(coord, rowNum, colNum, rawValue, value,
                null, null, null, null, null);
    }

    private static NormalizedCell normalizeFormulaCell(Cell formulaCell, Cell valueCell,
            String coord, int rowNum, int colNum, boolean cacheFresh) {
        String formulaText = formulaCell.getCellFormula();
        String formulaNormalized = FormulaNormalizer.normalize(formulaText);

        boolean hasCachedValue = valueCell instanceof XSSFCell xssfCell
                && xssfCell.getCTCell().isSetV();
        CellType cachedType = hasCachedValue
                ? valueCell.getCachedFormulaResultType()
                : CellType.BLANK;
        String cachedValue;
        CellValue cached;
        String cacheState;

        if (!hasCachedValue) {
            cachedValue = null;
            cached = CellValue.empty();
            cacheState = "missing";
        } else if (cachedType == CellType.BLANK) {
            cachedValue = "";
            cached = CellValue.empty();
            cacheState = cacheFresh ? "fresh" : "stale";
        } else {
            cachedValue = cachedValueString(valueCell, cachedType);
            cached = cachedType == CellType.ERROR
                    ? CellNormalizer.normalize(cachedValue)
                    : normalizeCachedValue(valueCell, cachedType, cachedValue);
            cacheState = cacheFresh ? "fresh" : "stale";
        }

        String valueType = cached.valueType().equals("empty") ? "formula" : cached.valueType();
        CellValue value = new CellValue(
                "formula", valueType,
                cached.textValue(), cached.displayValue(),
                cached.numericValue(), cached.boolValue(), cached.dateValue(),
                cached.coercedFromText(), cached.parsedQuantity(),
                cached.isError(), cached.errorType());

        return buildCell(coord, rowNum, colNum, "=" + formulaText, value,
                formulaText, formulaNormalized, "ok", cachedValue, cacheState);
    }

    private static CellValue normalizeCachedValue(Cell cell, CellType cachedType, String cachedValue) {
        if (cachedValue == null) {
            return CellValue.empty();
        }
        if (cachedType == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return CellNormalizer.normalizeDate(cell.getLocalDateTimeCellValue());
            }
            return numericValue(cachedValue);
        }
        return CellNormalizer.normalize(cachedValue);
    }

    private static CellValue numericValue(String rawValue) {
        return new CellValue(
                "number", "number", rawValue, rawValue,
                new java.math.BigDecimal(rawValue), null, null,
                false, null, false, null);
    }

    private static String cachedValueString(Cell cell, CellType cachedType) {
        return switch (cachedType) {
            case NUMERIC -> Double.toString(cell.getNumericCellValue());
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            case ERROR -> errorLiteral(cell.getErrorCellValue());
            default -> null;
        };
    }

    private static String errorLiteral(byte errorCode) {
        return ErrorEval.getText(errorCode);
    }

    private static NormalizedCell buildCell(String coord, int rowNum, int colNum,
            String rawValue, CellValue value, String formulaText,
            String formulaNormalized, String formulaState, String cachedValue,
            String cacheState) {
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
                value.errorType());
    }
}
