package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateWrite;
import com.resurgent.tev.parser.db.CellReading;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
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
        assertThat(amount.scale()).isEqualTo("unit");
        assertThat(new BigDecimal(amount.absoluteAmount())).isEqualByComparingTo("10");
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
            int minRow = Integer.MAX_VALUE;
            int minCol = Integer.MAX_VALUE;
            int maxRow = 1;
            int maxCol = 1;
            long worksheetId = cells.get(0).worksheetId();
            List<Long> members = new ArrayList<>();
            Map<Long, String> coords = new HashMap<>();
            for (InterpretationCellView cell : cells) {
                coords.put(cell.cellId(), cell.coord());
                if (cell.worksheetId() != worksheetId) {
                    continue;
                }
                members.add(cell.cellId());
                minRow = Math.min(minRow, cell.rowNum());
                minCol = Math.min(minCol, cell.colNum());
                maxRow = Math.max(maxRow, cell.rowNum());
                maxCol = Math.max(maxCol, cell.colNum());
            }
            repo.insertCandidate(new CandidateWrite(
                    summary.parseRunId(),
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
            new CellReadingWriter().replace(repo, summary.parseRunId());
            Map<String, CellReading> byCoord = new HashMap<>();
            for (CellReading reading : repo.selectCellReadingsForParseRun(summary.parseRunId())) {
                byCoord.put(coords.get(reading.cellId()), reading);
            }
            return byCoord;
        }
    }

    private XSSFWorkbook workbook(SheetSetup setup) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        setup.build(workbook.createSheet("Model"));
        return workbook;
    }

    @FunctionalInterface
    private interface SheetSetup {
        void build(Sheet sheet);
    }
}
