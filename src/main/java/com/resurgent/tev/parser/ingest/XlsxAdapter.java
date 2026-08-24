package com.resurgent.tev.parser.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
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
 *
 * <p><strong>Merged-cell aggregation contract.</strong> For a merged range the
 * top-left cell (anchor) owns the value with {@code value_source='cell'}; every
 * other cell in the range is a participant with {@code value_source='merged_anchor'},
 * {@code numeric_value=NULL}, {@code text_value=NULL}, and the anchor's value in
 * {@code display_value} only. Aggregation queries must therefore filter
 * {@code value_source='cell'} (or {@code is_merged_participant=0}) to avoid
 * double counting. This is asserted at the primary seam in
 * {@code XlsxAdapterTest.sqlAggregationFilteringValueSourceCellSumsAnchorOnce()}.
 */
public final class XlsxAdapter {

    /**
     * Legacy parse entry point. Returns sheets only; use {@link #parseWorkbook(Path)}
     * when workbook metadata is also required.
     */
    public List<XlsxSheet> parse(Path xlsx) throws IOException {
        return parseWorkbook(xlsx).sheets();
    }

    /**
     * Full parse entry point returning sheets plus workbook metadata.
     */
    public XlsxWorkbook parseWorkbook(Path xlsx) throws IOException {
        byte[] bytes = Files.readAllBytes(xlsx);
        try (Workbook formulaBook = open(bytes);
                Workbook valueBook = open(bytes)) {
            boolean cacheFresh = isCacheFresh(valueBook);
            int sheetCount = formulaBook.getNumberOfSheets();
            List<String> sheetNames = sheetNames(formulaBook);
            Map<String, String> definedNames = DefinedNameParser.parse(formulaBook);
            List<ExternalLinkIn> externalLinks = ExternalLinkParser.parse(formulaBook);
            WorkbookMetadata metadata = buildMetadata(formulaBook, sheetCount, sheetNames,
                    definedNames, externalLinks);

            List<XlsxSheet> sheets = new ArrayList<>(sheetCount);
            for (int i = 0; i < sheetCount; i++) {
                Sheet formulaSheet = formulaBook.getSheetAt(i);
                Sheet valueSheet = valueBook.getSheetAt(i);
                String state = CellGeometry.sheetState(formulaBook, i);
                sheets.add(parseSheet(formulaSheet, valueSheet, i, state, cacheFresh, definedNames));
            }
            return new XlsxWorkbook(sheets, metadata);
        }
    }

    private static WorkbookMetadata buildMetadata(Workbook workbook, int sheetCount,
            List<String> sheetNames, Map<String, String> definedNames,
            List<ExternalLinkIn> externalLinks) {
        String applicationName = null;
        String applicationVersion = null;
        if (workbook instanceof XSSFWorkbook xssf) {
            var extProps = xssf.getProperties().getExtendedProperties();
            if (extProps != null) {
                applicationName = extProps.getApplication();
                applicationVersion = extProps.getAppVersion();
            }
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        if (workbook instanceof XSSFWorkbook xssf) {
            POIXMLProperties.CoreProperties core = xssf.getProperties().getCoreProperties();
            putIfPresent(properties, "title", core.getTitle());
            putIfPresent(properties, "subject", core.getSubject());
            putIfPresent(properties, "creator", core.getCreator());
            putIfPresent(properties, "keywords", core.getKeywords());
            putIfPresent(properties, "description", core.getDescription());
            putIfPresent(properties, "identifier", core.getIdentifier());
            putIfPresent(properties, "revision", core.getRevision());
            putIfPresent(properties, "category", core.getCategory());

            POIXMLProperties.ExtendedProperties extProps = xssf.getProperties().getExtendedProperties();
            if (extProps != null) {
                properties.put("company", nullIfBlank(extProps.getCompany()));
                properties.put("manager", nullIfBlank(extProps.getManager()));
            }
        }
        properties.values().removeIf(v -> v == null);

        boolean isProtected = false;
        if (workbook instanceof XSSFWorkbook xssf) {
            isProtected = xssf.isStructureLocked();
        }

        String createdAt = null;
        String modifiedAt = null;
        if (workbook instanceof XSSFWorkbook xssf) {
            createdAt = timestamp(xssf.getProperties().getCoreProperties().getCreated());
            modifiedAt = timestamp(xssf.getProperties().getCoreProperties().getModified());
        }

        String calculationMode = null;
        Boolean fullCalcOnLoad = null;
        Boolean calcChainPresent = null;
        boolean iterativeCalc = false;
        Integer iterativeCount = null;
        if (workbook instanceof XSSFWorkbook xssf) {
            var calcPr = xssf.getCTWorkbook().getCalcPr();
            if (calcPr != null) {
                if (calcPr.getCalcMode() != null) {
                    calculationMode = calcPr.getCalcMode().toString();
                }
                fullCalcOnLoad = calcPr.getFullCalcOnLoad();
                iterativeCalc = calcPr.getIterate();
                if (calcPr.isSetIterateCount()) {
                    iterativeCount = (int) calcPr.getIterateCount();
                }
            }
            calcChainPresent = xssf.getCalculationChain() != null;
        }

        return new WorkbookMetadata(
                applicationName, applicationVersion, sheetCount, sheetNames,
                definedNames, properties, isProtected, createdAt, modifiedAt, externalLinks,
                calculationMode, fullCalcOnLoad, calcChainPresent, iterativeCalc, iterativeCount);
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        String normalized = nullIfBlank(value);
        if (normalized != null) {
            map.put(key, normalized);
        }
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String timestamp(Date date) {
        if (date == null) {
            return null;
        }
        return Instant.ofEpochMilli(date.getTime()).toString();
    }

    private static List<String> sheetNames(Workbook workbook) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetName(i));
        }
        return names;
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

    private static XlsxSheet parseSheet(Sheet formulaSheet, Sheet valueSheet,
            int index, String state, boolean cacheFresh, Map<String, String> definedNames) {
        boolean sheetHidden = !"visible".equals(state);
        List<CellRangeAddress> mergedRegions = formulaSheet.getMergedRegions();

        // Index every merged region by the coordinates it covers so that each
        // occupied cell can be classified as anchor, participant, or normal.
        Map<String, CellRangeAddress> regionByCoord = new HashMap<>();
        for (CellRangeAddress region : mergedRegions) {
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    regionByCoord.put(CellGeometry.coord(r, c), region);
                }
            }
        }

        // First pass: iterate the full row range (not just non-null rows) so
        // hidden rows that contain cells are ingested with the correct flag.
        List<NormalizedCell> baseCells = new ArrayList<>();
        Map<String, NormalizedCell> baseByCoord = new HashMap<>();
        int firstRow = formulaSheet.getFirstRowNum();
        int lastRow = formulaSheet.getLastRowNum();
        if (firstRow >= 0 && lastRow >= 0) {
            for (int rowIdx = firstRow; rowIdx <= lastRow; rowIdx++) {
                Row formulaRow = formulaSheet.getRow(rowIdx);
                if (formulaRow == null) {
                    continue;
                }
                Row valueRow = valueSheet.getRow(rowIdx);
                boolean rowHidden = formulaRow.getZeroHeight();
                for (Cell formulaCell : formulaRow) {
                    if (formulaCell == null) {
                        continue;
                    }
                    Cell valueCell = valueRow == null
                            ? null
                            : valueRow.getCell(formulaCell.getColumnIndex());
                    boolean colHidden = formulaSheet.isColumnHidden(formulaCell.getColumnIndex());
                    NormalizedCell normalized = normalizeCell(formulaCell, valueCell,
                            rowHidden, colHidden, sheetHidden, cacheFresh, definedNames);
                    if (normalized != null) {
                        baseCells.add(normalized);
                        baseByCoord.put(normalized.coord(), normalized);
                    }
                }
            }
        }

        // Second pass: apply merged-region semantics. Anchors own the value;
        // participants mirror the anchor's display value and are tagged so
        // aggregation queries can filter them out with value_source='cell'.
        List<NormalizedCell> cells = new ArrayList<>();
        Set<String> handled = new HashSet<>();
        for (NormalizedCell cell : baseCells) {
            CellRangeAddress region = regionByCoord.get(cell.coord());
            if (region == null) {
                cells.add(cell);
                handled.add(cell.coord());
                continue;
            }
            String anchorCoord = CellGeometry.coord(region.getFirstRow(), region.getFirstColumn());
            if (cell.coord().equals(anchorCoord)) {
                cells.add(NormalizedCellFactory.markAnchor(cell, region));
            } else {
                NormalizedCell anchor = baseByCoord.get(anchorCoord);
                cells.add(NormalizedCellFactory.createParticipant(anchor, region, cell.rowNum(), cell.colNum(),
                        cell.coord(), cell.rowHidden(), cell.colHidden(), cell.sheetHidden()));
            }
            handled.add(cell.coord());
        }

        // Emit participants for blank cells that fall inside a merged region.
        for (CellRangeAddress region : mergedRegions) {
            String anchorCoord = CellGeometry.coord(region.getFirstRow(), region.getFirstColumn());
            NormalizedCell anchor = baseByCoord.get(anchorCoord);
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    String coord = CellGeometry.coord(r, c);
                    if (handled.contains(coord)) {
                        continue;
                    }
                    boolean rowHidden = CellGeometry.isRowHidden(formulaSheet, r);
                    boolean colHidden = formulaSheet.isColumnHidden(c);
                    cells.add(NormalizedCellFactory.createParticipant(anchor, region, r + 1, c + 1, coord,
                            rowHidden, colHidden, sheetHidden));
                    handled.add(coord);
                }
            }
        }

        SheetBbox.Bbox bbox = SheetBbox.computeBbox(formulaSheet, baseCells, mergedRegions,
                commentAddresses(formulaSheet));
        int realContentRows = SheetBbox.countContentRows(cells);
        String dimensionsDeclared = dimensionsDeclared(formulaSheet);

        return new XlsxSheet(formulaSheet.getSheetName(), index, state, cells,
                bbox.minRow(), bbox.minCol(), bbox.maxRow(), bbox.maxCol(),
                dimensionsDeclared, realContentRows, mergedRegions.size());
    }

    private static NormalizedCell normalizeCell(Cell formulaCell, Cell valueCell,
            boolean rowHidden, boolean colHidden, boolean sheetHidden,
            boolean cacheFresh, Map<String, String> definedNames) {
        int rowNum = formulaCell.getRowIndex() + 1;
        int colNum = formulaCell.getColumnIndex() + 1;
        String coord = CellGeometry.coord(formulaCell.getRowIndex(), formulaCell.getColumnIndex());

        CellType formulaType = formulaCell.getCellType();
        if (formulaType == CellType.BLANK) {
            return null;
        }

        // Read the style once: it is the source for all presentation signals on this cell.
        CellStyle style = formulaCell.getCellStyle();
        Font font = formulaCell.getSheet().getWorkbook().getFontAt(style.getFontIndexAsInt());
        boolean hasBorder = style.getBorderTop() != BorderStyle.NONE
                || style.getBorderRight() != BorderStyle.NONE
                || style.getBorderBottom() != BorderStyle.NONE
                || style.getBorderLeft() != BorderStyle.NONE;
        NormalizedCell normalized;

        if (formulaType == CellType.FORMULA) {
            boolean hasCachedValue = valueCell instanceof XSSFCell xssfCell
                    && xssfCell.getCTCell().isSetV();
            normalized = FormulaCellNormalizer.normalizeFormulaCell(formulaCell.getCellFormula(), valueCell,
                    coord, rowNum, colNum, rowHidden, colHidden, sheetHidden,
                    cacheFresh, definedNames, hasCachedValue);
        } else {
            normalized = LiteralCellNormalizer.normalizeLiteralCell(valueCell, coord, rowNum, colNum,
                    rowHidden, colHidden, sheetHidden);
        }
        return normalized == null ? null : normalized.withStyle(font.getBold(),
                style.getFillPattern() != FillPatternType.NO_FILL, hasBorder,
                style.getDataFormatString());
    }

    private static String dimensionsDeclared(Sheet formulaSheet) {
        if (formulaSheet instanceof XSSFSheet xssfSheet
                && xssfSheet.getCTWorksheet() != null
                && xssfSheet.getCTWorksheet().getDimension() != null) {
            return xssfSheet.getCTWorksheet().getDimension().getRef();
        }
        return null;
    }

    private static List<CellAddress> commentAddresses(Sheet sheet) {
        List<CellAddress> addresses = new ArrayList<>();
        if (sheet instanceof XSSFSheet xssfSheet) {
            Map<CellAddress, ?> comments = xssfSheet.getCellComments();
            if (comments != null) {
                addresses.addAll(comments.keySet());
            }
        }
        return addresses;
    }
}
