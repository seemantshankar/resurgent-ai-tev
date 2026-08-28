package com.resurgent.tev.parser.ingest;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;

/**
 * Shared coordinate and sheet-state helpers used by both XLSX and XLS adapters.
 */
final class CellGeometry {

    private CellGeometry() {
    }

    static String sheetState(Workbook workbook, int index) {
        if (workbook.isSheetVeryHidden(index)) {
            return "veryHidden";
        }
        if (workbook.isSheetHidden(index)) {
            return "hidden";
        }
        return "visible";
    }

    static boolean isRowHidden(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        return row != null && row.getZeroHeight();
    }

    static String coord(int rowIndex, int colIndex) {
        return CellReference.convertNumToColString(colIndex) + (rowIndex + 1);
    }

    static boolean isBlankContent(Cell cell) {
        if (cell == null) {
            return true;
        }
        CellType type = cell.getCellType();
        if (type == CellType.BLANK) {
            return true;
        }
        if (type == CellType.STRING) {
            String text = cell.getStringCellValue();
            return text == null || text.isBlank();
        }
        return false;
    }
}
