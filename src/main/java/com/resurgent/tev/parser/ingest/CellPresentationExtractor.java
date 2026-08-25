package com.resurgent.tev.parser.ingest;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Color;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;

/**
 * Reads POI cell-style metadata into the shared {@link CellPresentation} contract.
 */
final class CellPresentationExtractor {

    private CellPresentationExtractor() {
    }

    static boolean isBlankContent(Cell cell) {
        CellType type = cell.getCellType();
        if (type == CellType.BLANK) {
            return true;
        }
        if (type == CellType.STRING) {
            String value = cell.getStringCellValue();
            return value == null || value.isBlank();
        }
        return false;
    }

    static CellPresentation extract(Cell cell) {
        Workbook workbook = cell.getSheet().getWorkbook();
        return extract(workbook, cell.getCellStyle());
    }

    static CellPresentation extractDefaults(Workbook workbook) {
        return extract(workbook, workbook.getCellStyleAt(0));
    }

    static boolean isWhitespaceOnlyString(Cell cell) {
        return cell.getCellType() == CellType.STRING
                && cell.getStringCellValue() != null
                && cell.getStringCellValue().isBlank();
    }

    static boolean shouldPersistStyledBlank(Cell cell) {
        if (!isWhitespaceOnlyString(cell)) {
            return false;
        }
        CellPresentation presentation = extract(cell);
        if (!presentation.differsFrom(extractDefaults(cell.getSheet().getWorkbook()))) {
            return false;
        }
        return presentation.hasMeaningfulBlankFormatting();
    }

    private static CellPresentation extract(Workbook workbook, CellStyle style) {
        Font font = workbook.getFontAt(style.getFontIndexAsInt());
        boolean hasBorder = style.getBorderTop() != BorderStyle.NONE
                || style.getBorderRight() != BorderStyle.NONE
                || style.getBorderBottom() != BorderStyle.NONE
                || style.getBorderLeft() != BorderStyle.NONE;
        FillPatternType fillPattern = style.getFillPattern();
        boolean hasFill = fillPattern != null && fillPattern != FillPatternType.NO_FILL;

        Short fontColorIndex = font.getColor();
        String fontColorRgb = rgb(font instanceof XSSFFont xssfFont ? xssfFont.getXSSFColor() : null);
        Short fillForegroundColorIndex = style.getFillForegroundColor();
        String fillForegroundRgb = colorRgb(style.getFillForegroundColorColor());

        return new CellPresentation(
                font.getBold(),
                font.getItalic(),
                font.getUnderline() != Font.U_NONE,
                hasFill,
                hasBorder,
                style.getDataFormatString(),
                fontColorIndex,
                fontColorRgb,
                fillForegroundColorIndex,
                fillForegroundRgb,
                fillPattern == null ? null : fillPattern.name());
    }

    private static String colorRgb(Color color) {
        return color instanceof XSSFColor xssfColor ? rgb(xssfColor) : null;
    }

    private static String rgb(XSSFColor color) {
        if (color == null) {
            return null;
        }
        byte[] rgb = color.getRGB();
        if (rgb == null || rgb.length < 3) {
            return null;
        }
        return String.format("#%02x%02x%02x", rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF);
    }
}
