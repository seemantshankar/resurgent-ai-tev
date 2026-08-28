package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.hssf.eventusermodel.HSSFEventFactory;
import org.apache.poi.hssf.eventusermodel.HSSFListener;
import org.apache.poi.hssf.eventusermodel.HSSFRequest;
import org.apache.poi.hssf.record.FormulaRecord;
import org.apache.poi.hssf.record.Record;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Primary seam for the legacy HSSF (.xls) adapter. Fixtures are generated with
 * Apache POI so the assertions exercise the real HSSF read path and the shared
 * normalization contract used by {@link XlsxAdapter}.
 */
class XlsAdapterTest {

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
     * Creates an HSSF workbook with a formula cell and then strips the parsed
     * formula tokens (Ptgs) from the {@link FormulaRecord} using the record event
     * API. When POI re-reads the file, {@link Cell#getCellFormula()} throws because
     * the Ptgs are empty, which is exactly the "formula unavailable from HSSF"
     * scenario the adapter must handle.
     */
    private Path writeWorkbookWithUnavailableFormula(String name) throws Exception {
        byte[] bytes;
        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Unavailable");
            Row row = sheet.createRow(0);
            Cell cell = row.createCell(0);
            cell.setCellFormula("1+1");
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateFormulaCell(cell);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            bytes = baos.toByteArray();
        }

        byte[] stripped = stripFormulaPtgs(bytes);
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            out.write(stripped);
        }
        return file;
    }

    private byte[] stripFormulaPtgs(byte[] bytes) throws Exception {
        List<Record> records = new java.util.ArrayList<>();
        HSSFRequest request = new HSSFRequest();
        request.addListenerForAllRecords((HSSFListener) record -> {
            if (record instanceof FormulaRecord fr) {
                fr.setParsedExpression(new Ptg[0]);
            }
            records.add(record);
        });
        new HSSFEventFactory().processWorkbookEvents(request, new POIFSFileSystem(new ByteArrayInputStream(bytes)));

        int size = 0;
        for (Record r : records) {
            size += r.getRecordSize();
        }
        byte[] wbBytes = new byte[size];
        int offset = 0;
        for (Record r : records) {
            offset += r.serialize(offset, wbBytes);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.createDocument(new ByteArrayInputStream(wbBytes), "Workbook");
            fs.writeFilesystem(out);
        }
        return out.toByteArray();
    }

    private Map<String, NormalizedCell> cellsByCoord(List<XlsxSheet> sheets) {
        return sheets.stream()
                .flatMap(s -> s.cells().stream())
                .collect(Collectors.toMap(NormalizedCell::coord, Function.identity()));
    }

    @Test
    void everyOccupiedCellLandsOnceWithCorrectTypes() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Mix");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(123.45);
            row.createCell(1).setCellValue("hello");
            row.createCell(2).setCellValue(true);

            Path xls = writeWorkbook(workbook, "mix.xls");
            List<XlsxSheet> sheets = new XlsAdapter().parse(xls);

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
    void errorsClassifyIntoExactEnum() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Errors");
            Row row = sheet.createRow(0);
            row.createCell(0, CellType.ERROR).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.NA.getCode());
            row.createCell(1, CellType.ERROR).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.REF.getCode());
            row.createCell(2, CellType.ERROR).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.DIV0.getCode());

            Path xls = writeWorkbook(workbook, "errors.xls");
            List<XlsxSheet> sheets = new XlsAdapter().parse(xls);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            assertThat(cells.get("A1").errorType()).isEqualTo("#N/A");
            assertThat(cells.get("A1").valueType()).isEqualTo("error");
            assertThat(cells.get("B1").errorType()).isEqualTo("#REF!");
            assertThat(cells.get("C1").errorType()).isEqualTo("#DIV/0!");
        }
    }

    @Test
    void hiddenAndVeryHiddenSheetsAreIngestedWithState() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            workbook.createSheet("Visible");
            workbook.createSheet("Hidden");
            workbook.createSheet("VeryHidden");
            workbook.setSheetHidden(1, true);
            workbook.setSheetVisibility(2, SheetVisibility.VERY_HIDDEN);

            Path xls = writeWorkbook(workbook, "hidden.xls");
            List<XlsxSheet> sheets = new XlsAdapter().parse(xls);

            assertThat(sheets).hasSize(3);
            assertThat(sheets.get(0).sheetState()).isEqualTo("visible");
            assertThat(sheets.get(1).sheetState()).isEqualTo("hidden");
            assertThat(sheets.get(2).sheetState()).isEqualTo("veryHidden");
        }
    }

    @Test
    void mergedRangeProducesAnchorOnceAndParticipantsMirrorDisplayValue() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Merged");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(100.0);
            sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 1)); // A1:B2

            Path xls = writeWorkbook(workbook, "merged.xls");
            List<XlsxSheet> sheets = new XlsAdapter().parse(xls);

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
    void hiddenRowColumnAndSheetFlagsAreRecordedOnCells() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
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

            Path xls = writeWorkbook(workbook, "hidden-flags.xls");
            List<XlsxSheet> sheets = new XlsAdapter().parse(xls);
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
    void formulaCellKeepsFormulaTextAndCachedValue() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Formulas");
            Row row = sheet.createRow(0);
            Cell a1 = row.createCell(0);
            a1.setCellValue(5.0);
            Cell b1 = row.createCell(1);
            b1.setCellFormula("A1*2");
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateFormulaCell(b1);

            Path xls = writeWorkbook(workbook, "formulas.xls");
            List<XlsxSheet> sheets = new XlsAdapter().parse(xls);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            NormalizedCell b1Out = cells.get("B1");
            assertThat(b1Out.rawType()).isEqualTo("formula");
            assertThat(b1Out.formulaText()).isEqualTo("A1*2");
            assertThat(b1Out.formulaState()).isEqualTo("ok");
            assertThat(b1Out.cachedValue()).isEqualTo("10.0");
            assertThat(b1Out.cacheState()).isEqualTo("fresh");
            assertThat(b1Out.numericValue()).isEqualByComparingTo("10");
            assertThat(b1Out.valueType()).isEqualTo("number");
        }
    }

    @Test
    void formulaUnavailableFromHssfIsMarkedUnavailable() throws Exception {
        Path xls = writeWorkbookWithUnavailableFormula("unavailable-formula.xls");
        List<XlsxSheet> sheets = new XlsAdapter().parse(xls);
        Map<String, NormalizedCell> cells = cellsByCoord(sheets);

        NormalizedCell a1 = cells.get("A1");
        assertThat(a1.rawType()).isEqualTo("formula");
        assertThat(a1.formulaText()).isNull();
        assertThat(a1.formulaState()).isEqualTo("unavailable");
        assertThat(a1.cachedValue()).isEqualTo("2.0");
        assertThat(a1.cacheState()).isEqualTo("fresh");
        assertThat(a1.numericValue()).isEqualByComparingTo("2");
        assertThat(a1.valueType()).isEqualTo("number");
    }

    @Test
    void numericTextCoercionHandlesIndianCurrencyAndParentheses() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Coerce");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("₹10,00,000.00");
            row.createCell(1).setCellValue("(1,234)");
            row.createCell(2).setCellValue("12.5%");
            row.createCell(3).setCellValue("Rs. 50,000");

            Path xls = writeWorkbook(workbook, "coerce.xls");
            List<XlsxSheet> sheets = new XlsAdapter().parse(xls);
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
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Qty");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("1Set");
            row.createCell(1).setCellValue("200 PC");
            row.createCell(2).setCellValue("L S");

            Path xls = writeWorkbook(workbook, "qty.xls");
            Map<String, NormalizedCell> cells = cellsByCoord(new XlsAdapter().parse(xls));

            assertThat(cells.get("A1").valueType()).isEqualTo("text");
            assertThat(cells.get("A1").textValue()).isEqualTo("1Set");
            assertThat(cells.get("B1").textValue()).isEqualTo("200 PC");
            assertThat(cells.get("C1").textValue()).isEqualTo("L S");
        }
    }

    @Test
    void dateCellKeepsSerialInRawAndDateValue() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Dates");
            Row row = sheet.createRow(0);
            CreationHelper helper = workbook.getCreationHelper();
            DataFormat format = helper.createDataFormat();
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(format.getFormat("yyyy-mm-dd"));
            Cell cell = row.createCell(0);
            cell.setCellValue(LocalDateTime.of(2024, 3, 15, 0, 0));
            cell.setCellStyle(style);

            Path xls = writeWorkbook(workbook, "dates.xls");
            List<XlsxSheet> sheets = new XlsAdapter().parse(xls);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            NormalizedCell a1 = cells.get("A1");
            assertThat(a1.rawType()).isEqualTo("date");
            assertThat(a1.valueType()).isEqualTo("date");
            assertThat(a1.dateValue()).isEqualTo(LocalDateTime.of(2024, 3, 15, 0, 0));
            assertThat(a1.rawValue()).isNotNull();
        }
    }

    @Test
    void bboxIncludesMergedRegionsBeyondOccupiedCells() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("MergedBbox");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(1.0);
            sheet.addMergedRegion(new CellRangeAddress(4, 6, 2, 3)); // C5:D7

            Path xls = writeWorkbook(workbook, "merged-bbox.xls");
            List<XlsxSheet> sheets = new XlsAdapter().parse(xls);
            XlsxSheet result = sheets.get(0);

            assertThat(result.bboxMinRow()).isEqualTo(1);
            assertThat(result.bboxMinCol()).isEqualTo(1);
            assertThat(result.bboxMaxRow()).isEqualTo(7);
            assertThat(result.bboxMaxCol()).isEqualTo(4);
            assertThat(result.declaredMerged()).isEqualTo(1);
        }
    }

    @Test
    void definedNamesAreCollectedInMetadata() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Names");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(1.0);
            workbook.createName().setNameName("MyRange");
            workbook.getName("MyRange").setRefersToFormula("A1");

            Path xls = writeWorkbook(workbook, "names.xls");
            XlsxWorkbook result = new XlsAdapter().parseWorkbook(xls);

            assertThat(result.metadata().definedNames()).containsEntry("MyRange", "A1");
        }
    }

    @Test
    void stringResultFormulaPreservesCachedText() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("StringFormula");
            Row row = sheet.createRow(0);
            Cell cell = row.createCell(0);
            cell.setCellFormula("\"hello\"&\"world\"");
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateFormulaCell(cell);

            Path xls = writeWorkbook(workbook, "string-formula.xls");
            List<XlsxSheet> sheets = new XlsAdapter().parse(xls);
            Map<String, NormalizedCell> cells = cellsByCoord(sheets);

            NormalizedCell a1 = cells.get("A1");
            assertThat(a1.rawType()).isEqualTo("formula");
            assertThat(a1.formulaText()).isEqualTo("\"hello\"&\"world\"");
            assertThat(a1.cachedValue()).isEqualTo("helloworld");
            assertThat(a1.valueType()).isEqualTo("text");
            assertThat(a1.textValue()).isEqualTo("helloworld");
        }
    }

    @Test
    void whitespaceOnlyStringWithPresentationIsNotStored() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("StyledBlank");
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            org.apache.poi.ss.usermodel.Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);

            Cell padded = sheet.createRow(0).createCell(0);
            padded.setCellValue("           ");
            padded.setCellStyle(style);

            sheet.createRow(1).createCell(0).setCellValue(" ");

            Path xls = writeWorkbook(workbook, "styled-blank.xls");
            Map<String, NormalizedCell> cells = cellsByCoord(new XlsAdapter().parse(xls));

            assertThat(cells).isEmpty();
        }
    }

    @Test
    void whitespaceOnlyStringWithNumberFormatOnlyIsNotStored() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("NumberFormatOnly");
            DataFormat dataFormat = workbook.createDataFormat();
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(dataFormat.getFormat("0.00_)"));

            Cell padded = sheet.createRow(0).createCell(0);
            padded.setCellValue(" ");
            padded.setCellStyle(style);

            Path xls = writeWorkbook(workbook, "number-format-blank.xls");
            Map<String, NormalizedCell> cells = cellsByCoord(new XlsAdapter().parse(xls));

            assertThat(cells).isEmpty();
        }
    }

    @Test
    void blankCellWithPresentationIsNotStoredWithoutStringPadding() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("BlankStyled");
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_BLUE.getIndex());
            style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            Cell blank = sheet.createRow(0).createCell(0);
            blank.setCellStyle(style);

            Path xls = writeWorkbook(workbook, "blank-styled.xls");
            Map<String, NormalizedCell> cells = cellsByCoord(new XlsAdapter().parse(xls));

            assertThat(cells).isEmpty();
        }
    }

}
