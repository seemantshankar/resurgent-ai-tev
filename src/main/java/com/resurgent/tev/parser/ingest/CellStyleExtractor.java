package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.db.CellStyle;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Color;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Reads Excel paint into the shared {@link CellStyle} flyweight (ADR 0013).
 * Colour prefers {@code #rrggbb} when XSSF RGB is available, else palette index.
 * Theme colours with no resolvable RGB stay null — never invent index {@code 0}.
 */
final class CellStyleExtractor {

    private CellStyleExtractor() {
    }

    static CellStyle extract(Cell cell) {
        if (cell == null) {
            return null;
        }
        Workbook workbook = cell.getSheet().getWorkbook();
        org.apache.poi.ss.usermodel.CellStyle style = cell.getCellStyle();
        Font font = workbook.getFontAt(style.getFontIndexAsInt());

        FillPatternType fillPattern = style.getFillPattern();
        boolean hasFill = fillPattern != null && fillPattern != FillPatternType.NO_FILL;

        return new CellStyle(
                font.getBold(),
                style.getDataFormatString(),
                hasFill ? foregroundColor(workbook, style) : null,
                hasFill && fillPattern != null ? fillPattern.name() : null,
                borderStyle(style.getBorderTop()),
                borderColor(workbook, style, BorderSide.TOP),
                borderStyle(style.getBorderRight()),
                borderColor(workbook, style, BorderSide.RIGHT),
                borderStyle(style.getBorderBottom()),
                borderColor(workbook, style, BorderSide.BOTTOM),
                borderStyle(style.getBorderLeft()),
                borderColor(workbook, style, BorderSide.LEFT));
    }

    /**
     * True when paint is worth keeping on an otherwise blank cell: bold, fill, or
     * any per-side border. Number format alone is not enough (column defaults).
     */
    static boolean hasMeaningfulAppearance(CellStyle style) {
        if (style == null) {
            return false;
        }
        if (Boolean.TRUE.equals(style.isBold())) {
            return true;
        }
        if (style.fillPattern() != null) {
            return true;
        }
        return style.borderTopStyle() != null
                || style.borderRightStyle() != null
                || style.borderBottomStyle() != null
                || style.borderLeftStyle() != null;
    }

    private enum BorderSide {
        TOP, RIGHT, BOTTOM, LEFT
    }

    private static String borderStyle(BorderStyle border) {
        if (border == null || border == BorderStyle.NONE) {
            return null;
        }
        return border.name();
    }

    private static String borderColor(Workbook workbook, org.apache.poi.ss.usermodel.CellStyle style,
            BorderSide side) {
        BorderStyle border = switch (side) {
            case TOP -> style.getBorderTop();
            case RIGHT -> style.getBorderRight();
            case BOTTOM -> style.getBorderBottom();
            case LEFT -> style.getBorderLeft();
        };
        if (border == null || border == BorderStyle.NONE) {
            return null;
        }
        if (style instanceof XSSFCellStyle xssfStyle) {
            XSSFColor color = switch (side) {
                case TOP -> xssfStyle.getTopBorderXSSFColor();
                case RIGHT -> xssfStyle.getRightBorderXSSFColor();
                case BOTTOM -> xssfStyle.getBottomBorderXSSFColor();
                case LEFT -> xssfStyle.getLeftBorderXSSFColor();
            };
            String resolved = resolveXssfColor(workbook, color);
            if (resolved != null || (color != null && color.isThemed())) {
                return resolved;
            }
        }
        short index = switch (side) {
            case TOP -> style.getTopBorderColor();
            case RIGHT -> style.getRightBorderColor();
            case BOTTOM -> style.getBottomBorderColor();
            case LEFT -> style.getLeftBorderColor();
        };
        return paletteIndex(index);
    }

    private static String foregroundColor(Workbook workbook, org.apache.poi.ss.usermodel.CellStyle style) {
        if (style instanceof XSSFCellStyle xssfStyle) {
            XSSFColor color = xssfStyle.getFillForegroundXSSFColor();
            String resolved = resolveXssfColor(workbook, color);
            if (resolved != null || (color != null && color.isThemed())) {
                return resolved;
            }
        }
        String rgb = colorRgb(style.getFillForegroundColorColor());
        if (rgb != null) {
            return rgb;
        }
        return paletteIndex(style.getFillForegroundColor());
    }

    private static String resolveXssfColor(Workbook workbook, XSSFColor color) {
        if (color == null) {
            return null;
        }
        String rgb = rgb(color);
        if (rgb != null) {
            return rgb;
        }
        String hex = color.getARGBHex();
        if (hex != null && hex.length() >= 6) {
            return "#" + hex.substring(hex.length() - 6).toLowerCase();
        }
        if (color.isThemed() && workbook instanceof XSSFWorkbook xssfWorkbook
                && xssfWorkbook.getTheme() != null) {
            try {
                XSSFColor themeColor = xssfWorkbook.getTheme().getThemeColor(color.getTheme());
                String themeRgb = rgb(themeColor);
                if (themeRgb != null) {
                    return themeRgb;
                }
            } catch (RuntimeException ignored) {
                // Incomplete theme tables leave colour unavailable.
            }
        }
        if (color.isThemed()) {
            return null;
        }
        return null;
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

    private static String paletteIndex(short index) {
        // 64 is POI's automatic/no-fill sentinel for indexed colours.
        if (index < 0 || index == 64) {
            return null;
        }
        return Short.toString(index);
    }
}
