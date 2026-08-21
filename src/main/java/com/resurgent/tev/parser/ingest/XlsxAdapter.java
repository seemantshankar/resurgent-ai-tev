package com.resurgent.tev.parser.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ss.formula.eval.ErrorEval;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
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
                String state = sheetState(formulaBook, i);
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

        return new WorkbookMetadata(
                applicationName, applicationVersion, sheetCount, sheetNames,
                definedNames, properties, isProtected, createdAt, modifiedAt, externalLinks);
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
            int index, String state, boolean cacheFresh, Map<String, String> definedNames) {
        boolean sheetHidden = !"visible".equals(state);
        List<CellRangeAddress> mergedRegions = formulaSheet.getMergedRegions();

        // Index every merged region by the coordinates it covers so that each
        // occupied cell can be classified as anchor, participant, or normal.
        Map<String, CellRangeAddress> regionByCoord = new HashMap<>();
        for (CellRangeAddress region : mergedRegions) {
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    regionByCoord.put(coord(r, c), region);
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
            String anchorCoord = coord(region.getFirstRow(), region.getFirstColumn());
            if (cell.coord().equals(anchorCoord)) {
                cells.add(markAnchor(cell, region));
            } else {
                NormalizedCell anchor = baseByCoord.get(anchorCoord);
                cells.add(createParticipant(anchor, region, cell.rowNum(), cell.colNum(),
                        cell.coord(), cell.rowHidden(), cell.colHidden(), cell.sheetHidden()));
            }
            handled.add(cell.coord());
        }

        // Emit participants for blank cells that fall inside a merged region.
        for (CellRangeAddress region : mergedRegions) {
            String anchorCoord = coord(region.getFirstRow(), region.getFirstColumn());
            NormalizedCell anchor = baseByCoord.get(anchorCoord);
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    String coord = coord(r, c);
                    if (handled.contains(coord)) {
                        continue;
                    }
                    boolean rowHidden = isRowHidden(formulaSheet, r);
                    boolean colHidden = formulaSheet.isColumnHidden(c);
                    cells.add(createParticipant(anchor, region, r + 1, c + 1, coord,
                            rowHidden, colHidden, sheetHidden));
                    handled.add(coord);
                }
            }
        }

        Bbox bbox = computeBbox(formulaSheet, baseCells, mergedRegions);
        int realContentRows = countContentRows(cells);
        String dimensionsDeclared = dimensionsDeclared(formulaSheet);

        return new XlsxSheet(formulaSheet.getSheetName(), index, state, cells,
                bbox.minRow, bbox.minCol, bbox.maxRow, bbox.maxCol,
                dimensionsDeclared, realContentRows, mergedRegions.size());
    }

    private static NormalizedCell normalizeCell(Cell formulaCell, Cell valueCell,
            boolean rowHidden, boolean colHidden, boolean sheetHidden,
            boolean cacheFresh, Map<String, String> definedNames) {
        int rowNum = formulaCell.getRowIndex() + 1;
        int colNum = formulaCell.getColumnIndex() + 1;
        String coord = CellReference.convertNumToColString(formulaCell.getColumnIndex()) + rowNum;

        CellType formulaType = formulaCell.getCellType();
        if (formulaType == CellType.BLANK) {
            return null;
        }

        if (formulaType == CellType.FORMULA) {
            return normalizeFormulaCell(formulaCell, valueCell, coord, rowNum, colNum,
                    rowHidden, colHidden, sheetHidden, cacheFresh, definedNames);
        }

        return normalizeLiteralCell(valueCell, coord, rowNum, colNum,
                rowHidden, colHidden, sheetHidden);
    }

    private static NormalizedCell normalizeLiteralCell(Cell cell, String coord,
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

        return buildCell(coord, rowNum, colNum, rawValue, value,
                null, null, null, null, null,
                rowHidden, colHidden, sheetHidden,
                null, null, null);
    }

    private static NormalizedCell normalizeFormulaCell(Cell formulaCell, Cell valueCell,
            String coord, int rowNum, int colNum,
            boolean rowHidden, boolean colHidden, boolean sheetHidden,
            boolean cacheFresh, Map<String, String> definedNames) {
        String formulaText = formulaCell.getCellFormula();
        String formulaNormalized = FormulaNormalizer.normalize(formulaText);

        FormulaReferences refs = FormulaReferenceExtractor.extract(formulaText, definedNames.keySet());
        String externalRef = firstOrNull(refs.externalRefs());
        String sheetRefs = jsonOrNull(refs.sheetRefs());
        String definedNameRefs = jsonOrNull(refs.definedNameRefs());

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
                formulaText, formulaNormalized, "ok", cachedValue, cacheState,
                rowHidden, colHidden, sheetHidden,
                externalRef, sheetRefs, definedNameRefs);
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

    private static String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String jsonOrNull(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return com.resurgent.tev.parser.db.Jsonb.toJson(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize reference list: " + values, e);
        }
    }

    private static NormalizedCell buildCell(String coord, int rowNum, int colNum,
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

    private static NormalizedCell markAnchor(NormalizedCell cell, CellRangeAddress region) {
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

    private static NormalizedCell createParticipant(NormalizedCell anchor, CellRangeAddress region,
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

    private static String dimensionsDeclared(Sheet formulaSheet) {
        if (formulaSheet instanceof XSSFSheet xssfSheet
                && xssfSheet.getCTWorksheet() != null
                && xssfSheet.getCTWorksheet().getDimension() != null) {
            return xssfSheet.getCTWorksheet().getDimension().getRef();
        }
        return null;
    }

    private static boolean isRowHidden(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        return row != null && row.getZeroHeight();
    }

    private static String coord(int rowIndex, int colIndex) {
        return CellReference.convertNumToColString(colIndex) + (rowIndex + 1);
    }

    private static Bbox computeBbox(Sheet sheet, List<NormalizedCell> baseCells,
            List<CellRangeAddress> mergedRegions) {
        Integer minRow = null;
        Integer minCol = null;
        Integer maxRow = null;
        Integer maxCol = null;
        for (NormalizedCell cell : baseCells) {
            minRow = min(minRow, cell.rowNum());
            minCol = min(minCol, cell.colNum());
            maxRow = max(maxRow, cell.rowNum());
            maxCol = max(maxCol, cell.colNum());
        }
        for (CellRangeAddress region : mergedRegions) {
            minRow = min(minRow, region.getFirstRow() + 1);
            minCol = min(minCol, region.getFirstColumn() + 1);
            maxRow = max(maxRow, region.getLastRow() + 1);
            maxCol = max(maxCol, region.getLastColumn() + 1);
        }
        for (org.apache.poi.ss.util.CellAddress address : commentAddresses(sheet)) {
            minRow = min(minRow, address.getRow() + 1);
            minCol = min(minCol, address.getColumn() + 1);
            maxRow = max(maxRow, address.getRow() + 1);
            maxCol = max(maxCol, address.getColumn() + 1);
        }
        for (CellReference ref : sameSheetPrecedents(sheet.getSheetName(), baseCells)) {
            minRow = min(minRow, ref.getRow() + 1);
            minCol = min(minCol, ref.getCol() + 1);
            maxRow = max(maxRow, ref.getRow() + 1);
            maxCol = max(maxCol, ref.getCol() + 1);
        }
        return new Bbox(minRow, minCol, maxRow, maxCol);
    }

    private static List<org.apache.poi.ss.util.CellAddress> commentAddresses(Sheet sheet) {
        List<org.apache.poi.ss.util.CellAddress> addresses = new ArrayList<>();
        if (sheet instanceof XSSFSheet xssfSheet) {
            Map<org.apache.poi.ss.util.CellAddress, ?> comments = xssfSheet.getCellComments();
            if (comments != null) {
                addresses.addAll(comments.keySet());
            }
        }
        return addresses;
    }

    private static List<CellReference> sameSheetPrecedents(String sheetName,
            List<NormalizedCell> baseCells) {
        List<CellReference> refs = new ArrayList<>();
        for (NormalizedCell cell : baseCells) {
            if (cell.formulaText() == null || cell.formulaText().isBlank()) {
                continue;
            }
            refs.addAll(FormulaReferenceExtractor.extractLocalRefs(
                    cell.formulaText(), sheetName));
        }
        return refs;
    }

    private static Integer min(Integer current, int candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }

    private static Integer max(Integer current, int candidate) {
        return current == null ? candidate : Math.max(current, candidate);
    }

    private static int countContentRows(List<NormalizedCell> cells) {
        Set<Integer> rows = new HashSet<>();
        for (NormalizedCell cell : cells) {
            if ("cell".equals(cell.valueSource())) {
                rows.add(cell.rowNum());
            }
        }
        return rows.size();
    }

    private record Bbox(Integer minRow, Integer minCol, Integer maxRow, Integer maxCol) {
    }
}
