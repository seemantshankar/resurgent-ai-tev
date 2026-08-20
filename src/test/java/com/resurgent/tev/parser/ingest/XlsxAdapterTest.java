package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
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
    void formulaNormalizationPreservesStringLiteralSpacing() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Formulas");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellFormula("\"A  B\"  &  C1");
            row.createCell(1).setCellFormula("SUM(  A1,  B1 )");
            row.createCell(2).setCellFormula("'My  Sheet'!A1 + B1");

            Path xlsx = writeWorkbook(workbook, "formulas.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            NormalizedCell a1 = cells.get("A1");
            assertThat(a1.formulaText()).isEqualTo("\"A  B\"  &  C1");
            assertThat(a1.formulaNormalized()).isEqualTo("\"A  B\" & C1");

            NormalizedCell b1 = cells.get("B1");
            assertThat(b1.formulaNormalized()).isEqualTo("SUM( A1, B1 )");

            NormalizedCell c1 = cells.get("C1");
            assertThat(c1.formulaNormalized()).isEqualTo("'My  Sheet'!A1 + B1");
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
    void quantityTextProducesParsedJson() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Qty");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("1Set");
            row.createCell(1).setCellValue("200 PC");
            row.createCell(2).setCellValue("L S");

            Path xlsx = writeWorkbook(workbook, "qty.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            NormalizedCell a1 = cells.get("A1");
            assertThat(a1.valueType()).isEqualTo("quantity_text");
            assertThat(a1.parsedQuantity().count()).isEqualByComparingTo("1");
            assertThat(a1.parsedQuantity().unit()).isEqualTo("Set");

            NormalizedCell b1 = cells.get("B1");
            assertThat(b1.valueType()).isEqualTo("quantity_text");
            assertThat(b1.parsedQuantity().count()).isEqualByComparingTo("200");
            assertThat(b1.parsedQuantity().unit()).isEqualTo("PC");

            NormalizedCell c1 = cells.get("C1");
            assertThat(c1.valueType()).isEqualTo("quantity_text");
            assertThat(c1.parsedQuantity().count()).isNull();
            assertThat(c1.parsedQuantity().unit()).isEqualTo("L S");
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
}
