package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateWrite;
import com.resurgent.tev.parser.db.CellReading;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cell readings from synthetic workbooks, through ingest and {@link CellReadingWriter}. */
class CellReadingWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void lakhsBecomeAbsoluteInr() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Land (in Rs. Lakhs)");
            sheet.getRow(0).createCell(1).setCellValue(12.5);
        }));

        CellReading land = readings.get("B1");
        assertThat(land.kind()).isEqualTo("money");
        assertThat(land.scale()).isEqualTo("lakh");
        assertThat(land.currency()).isEqualTo("INR");
        assertThat(land.unit()).isEmpty();
        assertThat(land.typeSource()).isEqualTo("input");
        assertThat(land.refusal()).isNull();
        assertThat(new BigDecimal(land.absoluteAmount())).isEqualByComparingTo("1250000");
    }

    @Test
    void rupeesPerSquareMetreIsARate() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Land rate (Rs/Sqm)");
            sheet.getRow(0).createCell(1).setCellValue(2500);
        }));

        CellReading rate = readings.get("B1");
        assertThat(rate.kind()).isEqualTo("rate");
        assertThat(rate.unit()).isEqualTo("sqm");
        assertThat(rate.currency()).isEqualTo("INR");
        assertThat(rate.scale()).isEqualTo("unit");
        assertThat(rate.absoluteAmount()).isNull();
        assertThat(rate.typeSource()).isEqualTo("input");
    }

    @Test
    void quantityTimesRateIsMoney() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Area (sqm)");
            sheet.getRow(0).createCell(1).setCellValue(100);
            sheet.createRow(1).createCell(0).setCellValue("Rate (Rs/Sqm)");
            sheet.getRow(1).createCell(1).setCellValue(40);
            sheet.createRow(2).createCell(1).setCellFormula("B1*B2");
        }));

        CellReading product = readings.get("B3");
        assertThat(product.kind()).isEqualTo("money");
        assertThat(product.currency()).isEqualTo("INR");
        assertThat(product.unit()).isEmpty();
        assertThat(product.scale()).isEqualTo("unit");
        assertThat(product.typeSource()).isEqualTo("derived");
        assertThat(product.refusal()).isNull();
        assertThat(new BigDecimal(product.absoluteAmount())).isEqualByComparingTo("4000");
    }

    @Test
    void assamPinMakesBareDollarInr() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Guwahati");
            sheet.createRow(1).createCell(0).setCellValue("Kamrup Rural");
            sheet.createRow(2).createCell(0).setCellValue("Assam");
            sheet.createRow(3).createCell(0).setCellValue("PIN-781122");
            Cell amount = sheet.createRow(5).createCell(1);
            amount.setCellValue(10);
            CellStyle style = sheet.getWorkbook().createCellStyle();
            DataFormat format = sheet.getWorkbook().createDataFormat();
            style.setDataFormat(format.getFormat("$#,##0.00"));
            amount.setCellStyle(style);
        }));

        CellReading amount = readings.get("B6");
        assertThat(amount.kind()).isEqualTo("money");
        assertThat(amount.currency()).isEqualTo("INR");
        // A currency format names the currency, not the scale: nothing says these are rupees.
        assertThat(amount.scale()).isNull();
        assertThat(amount.absoluteAmount()).isNull();
    }

    @Test
    void explicitUsdBeatsTheAddress() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Guwahati");
            sheet.createRow(1).createCell(0).setCellValue("Assam");
            sheet.createRow(2).createCell(0).setCellValue("PIN-781122");
            sheet.createRow(4).createCell(0).setCellValue("(in USD)");
            sheet.getRow(4).createCell(1).setCellValue(10);
            Cell bare = sheet.createRow(6).createCell(1);
            bare.setCellValue(8);
            CellStyle style = sheet.getWorkbook().createCellStyle();
            style.setDataFormat(sheet.getWorkbook().createDataFormat().getFormat("$#,##0"));
            bare.setCellStyle(style);
        }));

        CellReading amount = readings.get("B5");
        assertThat(amount.kind()).isEqualTo("money");
        assertThat(amount.currency()).isEqualTo("USD");
        assertThat(amount.typeSource()).isEqualTo("input");
        assertThat(readings.get("B7").currency()).isEqualTo("INR");
    }

    @Test
    void kindConflictStaysUntyped() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Cost (in Rs.)");
            sheet.getRow(0).createCell(1).setCellValue(10);
            sheet.createRow(1).createCell(0).setCellValue("Area (sqm)");
            sheet.getRow(1).createCell(1).setCellValue(4);
            sheet.createRow(2).createCell(1).setCellFormula("B1+B2");
        }));

        CellReading mixed = readings.get("B3");
        assertThat(mixed.kind()).isNull();
        assertThat(mixed.scale()).isNull();
        assertThat(mixed.absoluteAmount()).isNull();
        assertThat(mixed.typeSource()).isNull();
        assertThat(mixed.refusal()).isEqualTo("kind_conflict");
    }

    @Test
    void sheetTitleScaleTypesBareMoneyInputs() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lacs)");
            sheet.createRow(2).createCell(0).setCellValue("Land cost");
            sheet.getRow(2).createCell(1).setCellValue(12.5);
            sheet.createRow(3).createCell(0).setCellValue("Occupancy %");
            sheet.getRow(3).createCell(1).setCellValue(70);
        }));

        assertThat(readings.get("B3").kind()).isEqualTo("money");
        assertThat(readings.get("B3").scale()).isEqualTo("lakh");
        assertThat(readings.get("B4").kind()).isEqualTo("percent");
        assertThat(readings.get("B4").scale()).isEqualTo("unit");
    }

    @Test
    void labelScaleBeatsTheSheetTitle() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lacs)");
            sheet.createRow(2).createCell(0).setCellValue("Machinery cost (Rs. crore)");
            sheet.getRow(2).createCell(1).setCellValue(3);
        }));

        assertThat(readings.get("B3").scale()).isEqualTo("crore");
    }

    @Test
    void twoDifferentTitleScalesStateNothing() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lacs)");
            sheet.createRow(1).createCell(0).setCellValue("Detail (Amount in Rs)");
            sheet.createRow(3).createCell(0).setCellValue("Land cost");
            sheet.getRow(3).createCell(1).setCellValue(12.5);
        }));

        assertThat(readings.get("B4").kind()).isEqualTo("money");
        assertThat(readings.get("B4").scale()).isNull();
        assertThat(readings.get("B4").absoluteAmount()).isNull();
    }

    @Test
    void moneyNothingStatesHasNoScaleRatherThanRupees() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Land cost");
            sheet.getRow(0).createCell(1).setCellValue(12.5);
            sheet.createRow(1).createCell(0).setCellValue("Building cost");
            sheet.getRow(1).createCell(1).setCellValue(30);
            sheet.createRow(2).createCell(1).setCellFormula("B1+B2");
        }));

        for (String coord : List.of("B1", "B2", "B3")) {
            assertThat(readings.get(coord).kind()).isEqualTo("money");
            assertThat(readings.get(coord).scale()).isNull();
            assertThat(readings.get(coord).scaleBasis()).isNull();
            assertThat(readings.get(coord).absoluteAmount()).isNull();
        }
    }

    /**
     * The OM Arham chain: an interest schedule with no unit text, read by a cash flow and a
     * balance sheet that both state lakhs. The readers keep their statement, and the schedule
     * takes it from them instead of the totals refusing as mixed scale.
     */
    @Test
    void aStatedSheetReadingUnstatedMoneyFixesItsScale() throws Exception {
        Map<String, CellReading> readings = read(book(workbook -> {
            Sheet interest = workbook.createSheet("Interest");
            interest.createRow(0).createCell(0).setCellValue("Statement of interest and repayment");
            interest.createRow(2).createCell(0).setCellValue("Opening principal");
            interest.getRow(2).createCell(1).setCellValue(2640);
            interest.createRow(3).createCell(0).setCellValue("Repayment");
            interest.getRow(3).createCell(1).setCellValue(15);
            interest.createRow(4).createCell(0).setCellValue("Closing balance");
            interest.getRow(4).createCell(1).setCellFormula("B3-B4");

            Sheet cash = workbook.createSheet("CASH FLOW");
            cash.createRow(0).createCell(0).setCellValue("Rs. In Lakhs");
            cash.createRow(2).createCell(0).setCellValue("Repayment of term loan");
            cash.getRow(2).createCell(1).setCellFormula("Interest!B4");

            Sheet bs = workbook.createSheet("B S");
            bs.createRow(0).createCell(0).setCellValue("Rs. In Lakhs");
            bs.createRow(2).createCell(0).setCellValue("Secured term loan");
            bs.getRow(2).createCell(1).setCellFormula("Interest!B5-'CASH FLOW'!B3");
            bs.createRow(3).createCell(0).setCellValue("Current portion of loan");
            bs.getRow(3).createCell(1).setCellFormula("'CASH FLOW'!B3");
            bs.createRow(4).createCell(0).setCellValue("Share capital");
            bs.getRow(4).createCell(1).setCellValue(1200);
            bs.createRow(5).createCell(0).setCellValue("Total liabilities");
            bs.getRow(5).createCell(1).setCellFormula("SUM(B3:B5)");
        }));

        assertThat(readings.get("CASH FLOW!B3").scale()).isEqualTo("lakh");
        assertThat(readings.get("CASH FLOW!B3").scaleBasis()).isEqualTo("stated");
        assertThat(readings.get("B S!B3").scale()).isEqualTo("lakh");
        assertThat(readings.get("B S!B6").scale()).isEqualTo("lakh");
        assertThat(readings.get("B S!B6").refusal()).isNull();
        for (String coord : List.of("Interest!B3", "Interest!B4", "Interest!B5")) {
            assertThat(readings.get(coord).scale()).as(coord).isEqualTo("lakh");
            assertThat(readings.get(coord).scaleBasis()).as(coord).isEqualTo("inferred");
        }
        assertThat(new BigDecimal(readings.get("Interest!B3").absoluteAmount())).isEqualByComparingTo("264000000");
    }

    @Test
    void readersThatDisagreeLeaveTheScaleUnstated() throws Exception {
        Map<String, CellReading> readings = read(book(workbook -> {
            Sheet source = workbook.createSheet("Source");
            source.createRow(0).createCell(0).setCellValue("Loan amount");
            source.getRow(0).createCell(1).setCellValue(50);

            Sheet lakhs = workbook.createSheet("Lakhs");
            lakhs.createRow(0).createCell(0).setCellValue("Rs. In Lakhs");
            lakhs.createRow(2).createCell(0).setCellValue("Loan");
            lakhs.getRow(2).createCell(1).setCellFormula("Source!B1");

            Sheet crores = workbook.createSheet("Crores");
            crores.createRow(0).createCell(0).setCellValue("Rs. In Crores");
            crores.createRow(2).createCell(0).setCellValue("Loan");
            crores.getRow(2).createCell(1).setCellFormula("Source!B1");
        }));

        assertThat(readings.get("Source!B1").kind()).isEqualTo("money");
        assertThat(readings.get("Source!B1").scale()).isNull();
        assertThat(readings.get("Lakhs!B3").scale()).isEqualTo("lakh");
        assertThat(readings.get("Crores!B3").scale()).isEqualTo("crore");
    }

    @Test
    void unstatedMoneyDividedIntoLakhsWasRupees() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Land cost");
            sheet.getRow(0).createCell(1).setCellValue(2_500_000);
            sheet.getRow(0).createCell(2).setCellFormula("B1/100000");
        }));

        assertThat(readings.get("C1").scale()).isEqualTo("lakh");
        assertThat(readings.get("C1").scaleBasis()).isEqualTo("stated");
        assertThat(readings.get("B1").scale()).isEqualTo("unit");
        assertThat(readings.get("B1").scaleBasis()).isEqualTo("inferred");
    }

    @Test
    void rupeesDividedByHundredThousandAreLakhs() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Detail (Amount in Rs)");
            sheet.createRow(2).createCell(0).setCellValue("Land cost");
            sheet.getRow(2).createCell(1).setCellValue(2_500_000);
            sheet.getRow(2).createCell(2).setCellFormula("B3/100000");
        }));

        assertThat(readings.get("B3").scale()).isEqualTo("unit");
        assertThat(readings.get("C3").kind()).isEqualTo("money");
        assertThat(readings.get("C3").scale()).isEqualTo("lakh");
        assertThat(readings.get("C3").typeSource()).isEqualTo("derived");
    }

    @Test
    void lakhsTimesHundredThousandAreRupees() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lacs)");
            sheet.createRow(2).createCell(0).setCellValue("Land cost");
            sheet.getRow(2).createCell(1).setCellValue(25);
            sheet.getRow(2).createCell(2).setCellFormula("B3*100000");
        }));

        assertThat(readings.get("C3").scale()).isEqualTo("unit");
    }

    @Test
    void aConversionThatLandsOnNoScaleIsRefusedNotGuessed() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lacs)");
            sheet.createRow(2).createCell(0).setCellValue("Land cost");
            sheet.getRow(2).createCell(1).setCellValue(25);
            sheet.getRow(2).createCell(2).setCellFormula("B3/100000");
        }));

        assertThat(readings.get("C3").kind()).isNull();
        assertThat(readings.get("C3").refusal()).isEqualTo("kind_conflict");
    }

    @Test
    void aPlainFractionDoesNotChangeScale() throws Exception {
        Map<String, CellReading> readings = read(workbook(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Project cost (Rs. In Lacs)");
            sheet.createRow(2).createCell(0).setCellValue("Land cost");
            sheet.getRow(2).createCell(1).setCellValue(100);
            sheet.getRow(2).createCell(2).setCellFormula("B3*0.35");
        }));

        assertThat(readings.get("C3").scale()).isEqualTo("lakh");
    }

    private Map<String, CellReading> read(XSSFWorkbook workbook) throws Exception {
        Path xlsx = tempDir.resolve("reading.xlsx");
        try (OutputStream out = Files.newOutputStream(xlsx)) {
            XSSFFormulaEvaluator.evaluateAllFormulaCells(workbook);
            workbook.write(out);
        }
        workbook.close();
        Path db = tempDir.resolve("reading.db");
        IngestSummary summary = new IngestService().ingest(xlsx, 1L, db);
        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(database.connection());
            List<InterpretationCellView> cells =
                    repo.selectInterpretationCellsForParseRun(summary.parseRunId());
            Map<Long, String> sheetNames = new HashMap<>();
            for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(summary.parseRunId())) {
                sheetNames.put(sheet.worksheetId(), sheet.sheetName());
            }
            Map<Long, String> coords = new HashMap<>();
            for (InterpretationCellView cell : cells) {
                coords.put(cell.cellId(), cell.coord());
            }
            for (long worksheetId : sheetNames.keySet()) {
                insertBlock(repo, summary.parseRunId(), worksheetId, cells);
            }
            new CellReadingWriter().replace(repo, summary.parseRunId());
            Map<String, CellReading> byCoord = new HashMap<>();
            Map<Long, Long> sheetOf = new HashMap<>();
            for (InterpretationCellView cell : cells) {
                sheetOf.put(cell.cellId(), cell.worksheetId());
            }
            for (CellReading reading : repo.selectCellReadingsForParseRun(summary.parseRunId())) {
                String sheet = sheetNames.get(sheetOf.get(reading.cellId()));
                byCoord.put(sheet + "!" + coords.get(reading.cellId()), reading);
                if (sheetNames.size() == 1) {
                    byCoord.put(coords.get(reading.cellId()), reading);
                }
            }
            return byCoord;
        }
    }

    /** One synthetic block over the whole sheet, so labels resolve. */
    private static void insertBlock(
            WorkspaceRepository repo, long parseRunId, long worksheetId, List<InterpretationCellView> cells)
            throws Exception {
        int minRow = Integer.MAX_VALUE;
        int minCol = Integer.MAX_VALUE;
        int maxRow = 1;
        int maxCol = 1;
        List<Long> members = new ArrayList<>();
        for (InterpretationCellView cell : cells) {
            if (cell.worksheetId() != worksheetId) {
                continue;
            }
            members.add(cell.cellId());
            minRow = Math.min(minRow, cell.rowNum());
            minCol = Math.min(minCol, cell.colNum());
            maxRow = Math.max(maxRow, cell.rowNum());
            maxCol = Math.max(maxCol, cell.colNum());
        }
        if (members.isEmpty()) {
            return;
        }
        repo.insertCandidate(new CandidateWrite(
                parseRunId,
                worksheetId,
                "child",
                null,
                minRow,
                minCol,
                maxRow,
                maxCol,
                null,
                null,
                null,
                false,
                null,
                null,
                "synthetic block",
                "main"), members);
    }

    private XSSFWorkbook workbook(SheetSetup setup) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        setup.build(workbook.createSheet("Model"));
        return workbook;
    }

    private XSSFWorkbook book(java.util.function.Consumer<XSSFWorkbook> setup) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        setup.accept(workbook);
        return workbook;
    }

    @FunctionalInterface
    private interface SheetSetup {
        void build(Sheet sheet);
    }
}
