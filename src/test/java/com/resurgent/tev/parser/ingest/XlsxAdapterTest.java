package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Primary seam for the XLSX adapter + normalization contract. Every fixture is
 * generated with Apache POI so the assertions exercise the real XSSF read path.
 */
class XlsxAdapterTest {

    @TempDir
    Path tempDir;

    private Path writeWorkbook(Workbook workbook, String name) throws Exception {
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        workbook.close();
        return file;
    }

    /**
     * Writes a workbook and then strips the cached {@code <v>} element from every
     * formula cell in every worksheet. This lets the fixture represent formulas
     * that were saved without a cached value.
     */
    private Path writeWorkbookWithoutCachedFormulaValues(Workbook workbook, String name)
            throws Exception {
        Path file = writeWorkbook(workbook, name);
        // Remove the <v> cached-value element that follows each <f> formula element,
        // leaving the formula itself intact.
        Pattern pattern = Pattern.compile("(<f[^>]*>.*?</f>\\s*)<v[^>]*>[^<]*</v>", Pattern.DOTALL);
        try (OPCPackage pkg = OPCPackage.open(file.toFile())) {
            for (PackagePart part : pkg.getParts()) {
                PackagePartName partName = part.getPartName();
                if (partName == null) {
                    continue;
                }
                String nameStr = partName.getName();
                if (nameStr.startsWith("/xl/worksheets/sheet") && nameStr.endsWith(".xml")) {
                    String xml = new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    String modified = pattern.matcher(xml).replaceAll("$1");
                    if (!modified.equals(xml)) {
                        try (OutputStream out = part.getOutputStream()) {
                            out.write(modified.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        }
        return file;
    }

    /**
     * Writes a workbook and then patches the sheet dimension in the OPC package so
     * the declared range is larger than the real occupied range. POI recomputes the
     * dimension on write, so post-write patching is required for phantom fixtures.
     */
    private Path writeWorkbookWithPhantomDimension(Workbook workbook, String name,
            String dimensionRef) throws Exception {
        Path file = writeWorkbook(workbook, name);
        Pattern pattern = Pattern.compile("<dimension ref=\"[^\"]*\"/>");
        try (OPCPackage pkg = OPCPackage.open(file.toFile())) {
            for (PackagePart part : pkg.getParts()) {
                PackagePartName partName = part.getPartName();
                if (partName == null) {
                    continue;
                }
                String nameStr = partName.getName();
                if (nameStr.startsWith("/xl/worksheets/sheet") && nameStr.endsWith(".xml")) {
                    String xml = new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    String modified = pattern.matcher(xml).replaceAll(
                            "<dimension ref=\"" + dimensionRef + "\"/>");
                    if (!modified.equals(xml)) {
                        try (OutputStream out = part.getOutputStream()) {
                            out.write(modified.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        }
        return file;
    }

    private Map<String, NormalizedCell> cellsByCoord(List<XlsxSheet> sheets) {
        return sheets.stream()
                .flatMap(s -> s.cells().stream())
                .collect(Collectors.toMap(NormalizedCell::coord, Function.identity()));
    }

    @Test
    void everyOccupiedCellLandsOnceWithCorrectTypes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Mix");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(123.45);
            row.createCell(1).setCellValue("hello");
            row.createCell(2).setCellValue(true);

            Path xlsx = writeWorkbook(workbook, "mix.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);

            assertThat(sheets).hasSize(1);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);
            assertThat(cells).hasSize(3);

            NormalizedCell a1 = cells.get("A1");
            assertThat(a1.rawType()).isEqualTo("number");
            assertThat(a1.valueType()).isEqualTo("number");
            assertThat(a1.numericValue()).isEqualByComparingTo("123.45");

            NormalizedCell b1 = cells.get("B1");
            assertThat(b1.rawValue()).isEqualTo("hello");
            assertThat(b1.rawType()).isEqualTo("text");
            assertThat(b1.valueType()).isEqualTo("text");

            NormalizedCell c1 = cells.get("C1");
            assertThat(c1.rawValue()).isEqualTo("TRUE");
            assertThat(c1.rawType()).isEqualTo("bool");
            assertThat(c1.valueType()).isEqualTo("bool");
            assertThat(c1.boolValue()).isTrue();
            assertThat(c1.numericValue()).isNull();
        }
    }

    @Test
    void hiddenAndVeryHiddenSheetsAreIngestedWithState() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Visible");
            workbook.createSheet("Hidden");
            workbook.createSheet("VeryHidden");
            workbook.setSheetHidden(1, true);
            workbook.setSheetVisibility(2, SheetVisibility.VERY_HIDDEN);

            Path xlsx = writeWorkbook(workbook, "hidden.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);

            assertThat(sheets).hasSize(3);
            assertThat(sheets.get(0).sheetState()).isEqualTo("visible");
            assertThat(sheets.get(1).sheetState()).isEqualTo("hidden");
            assertThat(sheets.get(2).sheetState()).isEqualTo("veryHidden");
        }
    }

    @Test
    void errorsClassifyIntoExactEnum() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Errors");
            Row row = sheet.createRow(0);
            row.createCell(0, CellType.ERROR).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.NA.getCode());
            row.createCell(1, CellType.ERROR).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.REF.getCode());
            row.createCell(2, CellType.ERROR).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.DIV0.getCode());

            Path xlsx = writeWorkbook(workbook, "errors.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            assertThat(cells.get("A1").errorType()).isEqualTo("#N/A");
            assertThat(cells.get("A1").valueType()).isEqualTo("error");
            assertThat(cells.get("B1").errorType()).isEqualTo("#REF!");
            assertThat(cells.get("C1").errorType()).isEqualTo("#DIV/0!");
        }
    }

    @Test
    void formulaTextIsPreservedVerbatimAndNormalizedCleansWhitespace() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Formulas");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellFormula("\"A  B\"  &  C1");
            row.createCell(1).setCellFormula("SUM(  A1,  B1 )");
            row.createCell(2).setCellFormula("'My  Sheet'!A1 + B1");

            Path xlsx = writeWorkbook(workbook, "formulas.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            assertThat(cells.get("A1").formulaText()).isEqualTo("\"A  B\"  &  C1");
            assertThat(cells.get("A1").formulaNormalized()).isEqualTo("\"A  B\" & C1");

            assertThat(cells.get("B1").formulaText()).isEqualTo("SUM(  A1,  B1 )");
            assertThat(cells.get("B1").formulaNormalized()).isEqualTo("SUM( A1, B1 )");

            assertThat(cells.get("C1").formulaText()).isEqualTo("'My  Sheet'!A1 + B1");
            assertThat(cells.get("C1").formulaNormalized()).isEqualTo("'My  Sheet'!A1 + B1");
        }
    }

    @Test
    void numericTextCoercionHandlesIndianCurrencyAndParentheses() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Coerce");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("₹10,00,000.00");
            row.createCell(1).setCellValue("(1,234)");
            row.createCell(2).setCellValue("12.5%");
            row.createCell(3).setCellValue("Rs. 50,000");

            Path xlsx = writeWorkbook(workbook, "coerce.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            assertThat(cells.get("A1").numericValue()).isEqualByComparingTo("1000000.00");
            assertThat(cells.get("A1").coercedFromText()).isTrue();

            assertThat(cells.get("B1").numericValue()).isEqualByComparingTo("-1234");
            assertThat(cells.get("B1").coercedFromText()).isTrue();

            assertThat(cells.get("C1").numericValue()).isEqualByComparingTo("0.125");
            assertThat(cells.get("C1").coercedFromText()).isTrue();

            assertThat(cells.get("D1").numericValue()).isEqualByComparingTo("50000");
            assertThat(cells.get("D1").coercedFromText()).isTrue();
        }
    }

    @Test
    void quantityLikeTextIsStoredAsPlainText() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Qty");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("1Set");
            row.createCell(1).setCellValue("200 PC");
            row.createCell(2).setCellValue("L S");

            Path xlsx = writeWorkbook(workbook, "qty.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            assertThat(cells.get("A1").valueType()).isEqualTo("text");
            assertThat(cells.get("A1").textValue()).isEqualTo("1Set");
            assertThat(cells.get("B1").textValue()).isEqualTo("200 PC");
            assertThat(cells.get("C1").textValue()).isEqualTo("L S");
        }
    }

    @Test
    void formulaWithoutCachedValueHasMissingCacheStateAndNoInventedNumber() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Uncached");
            Row row = sheet.createRow(0);
            Cell cell = row.createCell(0);
            cell.setCellFormula("A1+B1");

            Path xlsx = writeWorkbookWithoutCachedFormulaValues(workbook, "uncached.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            NormalizedCell a1 = cells.get("A1");
            assertThat(a1.rawType()).isEqualTo("formula");
            assertThat(a1.cacheState()).isEqualTo("missing");
            assertThat(a1.cachedValue()).isNull();
            assertThat(a1.numericValue()).isNull();
            assertThat(a1.valueType()).isEqualTo("formula");
        }
    }

    @Test
    void manualCalcWorkbookMarksCachedFormulaValuesAsStale() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Stale");
            Row row = sheet.createRow(0);
            Cell cell = row.createCell(0);
            cell.setCellFormula("1+1");
            org.apache.poi.ss.usermodel.FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateFormulaCell(cell);
            workbook.setForceFormulaRecalculation(true);

            Path xlsx = writeWorkbook(workbook, "stale.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            NormalizedCell a1 = cells.get("A1");
            assertThat(a1.cacheState()).isEqualTo("stale");
            assertThat(a1.cachedValue()).isNotNull();
        }
    }

    @Test
    void dateCellKeepsSerialInRawAndDateValue() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Dates");
            Row row = sheet.createRow(0);
            org.apache.poi.ss.usermodel.CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            Cell cell = row.createCell(0);
            cell.setCellValue(LocalDateTime.of(2024, 3, 15, 0, 0));
            cell.setCellStyle(style);

            Path xlsx = writeWorkbook(workbook, "dates.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            NormalizedCell a1 = cells.get("A1");
            assertThat(a1.rawType()).isEqualTo("date");
            assertThat(a1.valueType()).isEqualTo("date");
            assertThat(a1.dateValue()).isEqualTo(LocalDateTime.of(2024, 3, 15, 0, 0));
            assertThat(a1.rawValue()).isNotNull();
        }
    }

    @Test
    void styledTitleCapturesSharedCellStyleAtTheAdapterOutput() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            Cell title = sheet.createRow(0).createCell(0);
            title.setCellValue("Project cost summary");
            org.apache.poi.ss.usermodel.CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("$#,##0.00"));
            style.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.YELLOW.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBottomBorderColor(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
            org.apache.poi.ss.usermodel.Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            title.setCellStyle(style);

            Cell twin = sheet.createRow(1).createCell(0);
            twin.setCellValue("same paint");
            twin.setCellStyle(style);

            Cell other = sheet.createRow(2).createCell(0);
            other.setCellValue("missing bottom border");
            org.apache.poi.ss.usermodel.CellStyle otherStyle = workbook.createCellStyle();
            otherStyle.setDataFormat(workbook.createDataFormat().getFormat("$#,##0.00"));
            otherStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.YELLOW.getIndex());
            otherStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            otherStyle.setFont(font);
            other.setCellStyle(otherStyle);

            Map<String, NormalizedCell> cells = cellsByCoord(
                    new XlsxAdapter().parse(writeWorkbook(workbook, "styled-title.xlsx")));

            NormalizedCell a1 = cells.get("A1");
            assertThat(a1.textValue()).isEqualTo("Project cost summary");
            assertThat(a1.cellStyle()).isNotNull();
            assertThat(a1.cellStyle().isBold()).isTrue();
            assertThat(a1.cellStyle().numberFormat()).isEqualTo("$#,##0.00");
            assertThat(a1.cellStyle().fillPattern()).isEqualTo("SOLID_FOREGROUND");
            assertThat(a1.cellStyle().fillFgColor()).matches("#[0-9a-f]{6}");
            assertThat(a1.cellStyle().borderBottomStyle()).isEqualTo("THIN");
            assertThat(a1.cellStyle().borderBottomColor()).isNotBlank();
            assertThat(a1.cellStyle().borderTopStyle()).isNull();

            assertThat(cells.get("A2").cellStyle()).isEqualTo(a1.cellStyle());
            assertThat(cells.get("A3").cellStyle()).isNotEqualTo(a1.cellStyle());
            assertThat(cells.get("A3").cellStyle().borderBottomStyle()).isNull();
        }
    }

    @Test
    void mergedRangeProducesAnchorOnceAndParticipantsMirrorDisplayValue() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Merged");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(100.0);
            sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 1)); // A1:B2

            Path xlsx = writeWorkbook(workbook, "merged.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);

            assertThat(sheets).hasSize(1);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);
            assertThat(cells).containsKeys("A1", "A2", "B1", "B2");

            NormalizedCell anchor = cells.get("A1");
            assertThat(anchor.isMergedAnchor()).isTrue();
            assertThat(anchor.isMergedParticipant()).isFalse();
            assertThat(anchor.valueSource()).isEqualTo("cell");
            assertThat(anchor.mergedRange()).isEqualTo("A1:B2");
            assertThat(anchor.numericValue()).isEqualByComparingTo("100");

            for (String coord : List.of("A2", "B1", "B2")) {
                NormalizedCell participant = cells.get(coord);
                assertThat(participant.isMergedParticipant()).isTrue();
                assertThat(participant.isMergedAnchor()).isFalse();
                assertThat(participant.valueSource()).isEqualTo("merged_anchor");
                assertThat(participant.mergedRange()).isEqualTo("A1:B2");
                assertThat(participant.displayValue()).isEqualTo(anchor.displayValue());
                assertThat(participant.numericValue()).isNull();
                assertThat(participant.textValue()).isNull();
                assertThat(participant.rawValue()).isNull();
                assertThat(participant.rawType()).isEqualTo("empty");
                assertThat(participant.valueType()).isEqualTo("empty");
            }

            BigDecimal sum = sheets.get(0).cells().stream()
                    .filter(c -> "cell".equals(c.valueSource()))
                    .map(NormalizedCell::numericValue)
                    .reduce(BigDecimal.ZERO, (a, b) -> b == null ? a : a.add(b));
            assertThat(sum).isEqualByComparingTo("100");
        }
    }

    @Test
    void mergedParticipantReplacesStaleCellContent() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("MergedStale");
            Row row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue(10.0);
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue(99.0); // stale content inside merged region
            sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 0)); // A1:A2

            Path xlsx = writeWorkbook(workbook, "merged-stale.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            assertThat(cells.get("A1").numericValue()).isEqualByComparingTo("10");
            NormalizedCell a2 = cells.get("A2");
            assertThat(a2.isMergedParticipant()).isTrue();
            assertThat(a2.valueSource()).isEqualTo("merged_anchor");
            assertThat(a2.numericValue()).isNull();
            assertThat(a2.displayValue()).isEqualTo("10.0");
        }
    }

    @Test
    void hiddenRowColumnAndSheetFlagsAreRecordedOnCells() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("HiddenFlags");
            workbook.setSheetHidden(0, true);

            Row row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue("visible row, visible col");
            row0.createCell(2).setCellValue("visible row, hidden col");
            sheet.setColumnHidden(2, true);

            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("hidden row, visible col");
            row2.setZeroHeight(true);

            Row row5 = sheet.createRow(5);
            row5.createCell(1).setCellValue("hidden row beyond blank gap");
            row5.setZeroHeight(true);

            Path xlsx = writeWorkbook(workbook, "hidden-flags.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            assertThat(sheets.get(0).sheetState()).isEqualTo("hidden");

            NormalizedCell a1 = cells.get("A1");
            assertThat(a1.rowHidden()).isFalse();
            assertThat(a1.colHidden()).isFalse();
            assertThat(a1.sheetHidden()).isTrue();

            NormalizedCell c1 = cells.get("C1");
            assertThat(c1.rowHidden()).isFalse();
            assertThat(c1.colHidden()).isTrue();
            assertThat(c1.sheetHidden()).isTrue();

            NormalizedCell a3 = cells.get("A3");
            assertThat(a3.rowHidden()).isTrue();
            assertThat(a3.colHidden()).isFalse();
            assertThat(a3.sheetHidden()).isTrue();

            NormalizedCell b6 = cells.get("B6");
            assertThat(b6.rowHidden()).isTrue();
            assertThat(b6.colHidden()).isFalse();
            assertThat(b6.sheetHidden()).isTrue();
        }
    }

    @Test
    void computedBboxIgnoresPhantomDeclaredDimensions() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Phantom");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(1.0);
            row.createCell(1).setCellValue(2.0);

            // POI recomputes the dimension on write, so patch it afterwards.
            Path xlsx = writeWorkbookWithPhantomDimension(workbook, "phantom.xlsx", "A1:Z100");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            XlsxSheet result = sheets.get(0);

            assertThat(result.dimensionsDeclared()).isEqualTo("A1:Z100");
            assertThat(result.bboxMinRow()).isEqualTo(1);
            assertThat(result.bboxMinCol()).isEqualTo(1);
            assertThat(result.bboxMaxRow()).isEqualTo(1);
            assertThat(result.bboxMaxCol()).isEqualTo(2);
            assertThat(result.realContentRows()).isEqualTo(1);
        }
    }

    @Test
    void bboxIncludesMergedRegionsBeyondOccupiedCells() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("MergedBbox");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(1.0);
            sheet.addMergedRegion(new CellRangeAddress(4, 6, 2, 3)); // C5:D7

            Path xlsx = writeWorkbook(workbook, "merged-bbox.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            XlsxSheet result = sheets.get(0);

            assertThat(result.bboxMinRow()).isEqualTo(1);
            assertThat(result.bboxMinCol()).isEqualTo(1);
            assertThat(result.bboxMaxRow()).isEqualTo(7);
            assertThat(result.bboxMaxCol()).isEqualTo(4);
            assertThat(result.declaredMerged()).isEqualTo(1);
        }
    }

    @Test
    void sqlAggregationFilteringValueSourceCellSumsAnchorOnce() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("SqlMerged");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(250.0);
            sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 1)); // A1:B2

            Path xlsx = writeWorkbook(workbook, "sql-merged.xlsx");
            Path dbPath = tempDir.resolve("sql-merged.db");
            new IngestService().ingest(xlsx, 1L, dbPath);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) AS cnt FROM cell WHERE value_source = 'cell'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("cnt")).isEqualTo(1);
                }
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT SUM(numeric_value) AS total FROM cell"
                                + " WHERE value_source = 'cell'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBigDecimal("total")).isEqualByComparingTo("250");
                }
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) AS cnt FROM cell WHERE value_source = 'merged_anchor'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("cnt")).isEqualTo(3);
                }
            }
        }
    }
}
