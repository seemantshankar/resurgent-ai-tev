package com.resurgent.tev.parser.ingest;

import java.io.IOException;
import java.io.InputStream;
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
import org.apache.poi.hpsf.DocumentSummaryInformation;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

/**
 * Legacy {@code .xls} (HSSF) adapter. Returns the same {@link XlsxWorkbook} type
 * as {@link XlsxAdapter} so {@link IngestService} can reuse the downstream XLSX
 * persistence path.
 *
 * <p>Flag handling is intentionally not implemented here: {@link IngestService}
 * rejects {@code .xls} intake with {@link RejectionReason#XLS_DISABLED} when
 * {@code xlsEnabled} is false. When the flag is true, this adapter is invoked.
 *
 * <p>Every sheet, including hidden and veryHidden sheets, is emitted as an
 * {@link XlsxSheet} with the same normalized canonical cells as the XLSX adapter.
 *
 * <p><strong>Formula contract.</strong> HSSF exposes most formula text through
 * {@link Cell#getCellFormula()}, but some records cannot be decoded back to a
 * formula string. When the formula text is unavailable, the cell is still typed
 * as {@code raw_type='formula'} but {@code formula_text} is {@code null},
 * {@code formula_normalized} is {@code null}, and {@code formula_state} is
 * {@code 'unavailable'}. The cached value, when present, is preserved; the
 * adapter never invents or guesses formula text.
 */
public final class XlsAdapter {

    /**
     * Legacy parse entry point. Returns sheets only; use {@link #parseWorkbook(Path)}
     * when workbook metadata is also required.
     */
    public List<XlsxSheet> parse(Path xls) throws IOException {
        return parseWorkbook(xls).sheets();
    }

    /**
     * Full parse entry point returning sheets plus workbook metadata.
     */
    public XlsxWorkbook parseWorkbook(Path xls) throws IOException {
        try (Workbook workbook = open(xls)) {
            boolean cacheFresh = isCacheFresh(workbook);
            int sheetCount = workbook.getNumberOfSheets();
            List<String> sheetNames = sheetNames(workbook);
            Map<String, String> definedNames = DefinedNameParser.parse(workbook);
            List<ExternalLinkIn> externalLinks = List.of();
            WorkbookMetadata metadata = buildMetadata(workbook, sheetCount, sheetNames,
                    definedNames, externalLinks);

            List<XlsxSheet> sheets = new ArrayList<>(sheetCount);
            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String state = CellGeometry.sheetState(workbook, i);
                sheets.add(parseSheet(sheet, i, state, cacheFresh, definedNames));
            }
            return new XlsxWorkbook(sheets, metadata);
        }
    }

    private static Workbook open(Path xls) throws IOException {
        try (InputStream in = Files.newInputStream(xls)) {
            return WorkbookFactory.create(in);
        }
    }

    private static boolean isCacheFresh(Workbook workbook) {
        return !workbook.getForceFormulaRecalculation();
    }

    private static List<String> sheetNames(Workbook workbook) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetName(i));
        }
        return names;
    }

    private static WorkbookMetadata buildMetadata(Workbook workbook, int sheetCount,
            List<String> sheetNames, Map<String, String> definedNames,
            List<ExternalLinkIn> externalLinks) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (workbook instanceof HSSFWorkbook hssf) {
            SummaryInformation si = hssf.getSummaryInformation();
            if (si != null) {
                putIfPresent(properties, "title", si.getTitle());
                putIfPresent(properties, "subject", si.getSubject());
                putIfPresent(properties, "creator", si.getAuthor());
                putIfPresent(properties, "keywords", si.getKeywords());
                putIfPresent(properties, "description", si.getComments());
            }
            DocumentSummaryInformation dsi = hssf.getDocumentSummaryInformation();
            if (dsi != null) {
                putIfPresent(properties, "company", dsi.getCompany());
                putIfPresent(properties, "manager", dsi.getManager());
                putIfPresent(properties, "category", dsi.getCategory());
            }
        }

        boolean isProtected = workbook instanceof HSSFWorkbook hssf && hssf.isWriteProtected();
        String createdAt = null;
        String modifiedAt = null;
        if (workbook instanceof HSSFWorkbook hssf) {
            SummaryInformation si = hssf.getSummaryInformation();
            if (si != null) {
                createdAt = timestamp(si.getCreateDateTime());
                modifiedAt = timestamp(si.getLastSaveDateTime());
            }
        }

        // .xls (HSSF) does not expose calcPr-equivalent calculation metadata via POI;
        // these fields are left null/false and only populated for XLSX inputs.
        return new WorkbookMetadata(
                null, null, sheetCount, sheetNames,
                definedNames, properties, isProtected, createdAt, modifiedAt, externalLinks,
                null, null, null, false, null);
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

    private static XlsxSheet parseSheet(Sheet sheet, int index, String state,
            boolean cacheFresh, Map<String, String> definedNames) {
        boolean sheetHidden = !"visible".equals(state);
        List<CellRangeAddress> mergedRegions = sheet.getMergedRegions();

        Map<String, CellRangeAddress> regionByCoord = new HashMap<>();
        for (CellRangeAddress region : mergedRegions) {
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    regionByCoord.put(CellGeometry.coord(r, c), region);
                }
            }
        }

        List<NormalizedCell> baseCells = new ArrayList<>();
        Map<String, NormalizedCell> baseByCoord = new HashMap<>();
        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();
        if (firstRow >= 0 && lastRow >= 0) {
            for (int rowIdx = firstRow; rowIdx <= lastRow; rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) {
                    continue;
                }
                boolean rowHidden = row.getZeroHeight();
                for (Cell cell : row) {
                    if (cell == null) {
                        continue;
                    }
                    boolean colHidden = sheet.isColumnHidden(cell.getColumnIndex());
                    NormalizedCell normalized = normalizeCell(cell, rowHidden, colHidden,
                            sheetHidden, cacheFresh, definedNames);
                    if (normalized != null) {
                        baseCells.add(normalized);
                        baseByCoord.put(normalized.coord(), normalized);
                    }
                }
            }
        }

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

        for (CellRangeAddress region : mergedRegions) {
            String anchorCoord = CellGeometry.coord(region.getFirstRow(), region.getFirstColumn());
            NormalizedCell anchor = baseByCoord.get(anchorCoord);
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    String coord = CellGeometry.coord(r, c);
                    if (handled.contains(coord)) {
                        continue;
                    }
                    boolean rowHidden = CellGeometry.isRowHidden(sheet, r);
                    boolean colHidden = sheet.isColumnHidden(c);
                    cells.add(NormalizedCellFactory.createParticipant(anchor, region, r + 1, c + 1, coord,
                            rowHidden, colHidden, sheetHidden));
                    handled.add(coord);
                }
            }
        }

        SheetBbox.Bbox bbox = SheetBbox.computeBbox(sheet, baseCells, mergedRegions, List.of());
        int realContentRows = SheetBbox.countContentRows(cells);

        return new XlsxSheet(sheet.getSheetName(), index, state, cells,
                bbox.minRow(), bbox.minCol(), bbox.maxRow(), bbox.maxCol(),
                null, realContentRows, mergedRegions.size());
    }

    private static NormalizedCell normalizeCell(Cell cell, boolean rowHidden, boolean colHidden,
            boolean sheetHidden, boolean cacheFresh, Map<String, String> definedNames) {
        int rowNum = cell.getRowIndex() + 1;
        int colNum = cell.getColumnIndex() + 1;
        String coord = CellGeometry.coord(cell.getRowIndex(), cell.getColumnIndex());

        CellType type = cell.getCellType();
        if (type == CellType.BLANK) {
            return null;
        }

        if (type == CellType.FORMULA) {
            String formulaText = readFormulaText(cell);
            return FormulaCellNormalizer.normalizeFormulaCell(formulaText, cell,
                    coord, rowNum, colNum, rowHidden, colHidden, sheetHidden,
                    cacheFresh, definedNames, true);
        }

        return LiteralCellNormalizer.normalizeLiteralCell(cell, coord, rowNum, colNum,
                rowHidden, colHidden, sheetHidden);
    }

    /**
     * Reads the formula text from a formula cell, returning {@code null} when HSSF
     * cannot expose it. Never invents or guesses a formula string.
     */
    private static String readFormulaText(Cell cell) {
        try {
            String formula = cell.getCellFormula();
            return formula == null || formula.isBlank() ? null : formula;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
