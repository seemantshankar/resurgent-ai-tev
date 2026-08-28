package com.resurgent.tev.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Primary-seam test for {@code tev-parse redact}: ingest then redact, assert
 * output file behaviour.
 */
class RedactCommandTest {

    @TempDir
    Path tempDir;

    private record RunResult(int exitCode, String stdout, String stderr) {}

    private RunResult run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = Main.commandLine()
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
        return new RunResult(exit, out.toString(), err.toString());
    }

    private Path writeRedactFixture() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Assumptions");
            Row labels = sheet.createRow(0);
            labels.createCell(0).setCellValue("Revenue");
            labels.createCell(1).setCellValue("Growth rate");

            Row values = sheet.createRow(1);
            values.createCell(0).setCellValue(1_000_000.0);
            values.createCell(1).setCellValue(0.12);

            Row formula = sheet.createRow(2);
            formula.createCell(0).setCellFormula("B2*0.12");

            Row amountText = sheet.createRow(3);
            amountText.createCell(0).setCellValue("₹10,00,000");

            Row merged = sheet.createRow(4);
            merged.createCell(0).setCellValue(500.0);
            Row mergedRow2 = sheet.createRow(5);
            mergedRow2.createCell(1).setCellValue(999.0);
            sheet.addMergedRegion(new CellRangeAddress(4, 5, 0, 1));

            Path file = tempDir.resolve("Project-FM.xlsx");
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
            return file;
        }
    }

    @Test
    void ingestThenRedact_writesRedactedFileAndPreservesFormulasAndLabels() throws Exception {
        Path input = writeRedactFixture();
        Path db = tempDir.resolve("workspace.db");
        Path outputDir = tempDir.resolve("redacted");

        RunResult ingest = run("ingest", "--input", input.toString(),
                "--mandate-id", "1", "--db", db.toString());
        assertThat(ingest.exitCode()).isZero();

        RunResult redact = run("redact",
                "--input", input.toString(),
                "--db", db.toString(),
                "--mandate-id", "1",
                "--sheet", "Assumptions",
                "--output-dir", outputDir.toString());
        assertThat(redact.exitCode()).isZero();
        assertThat(redact.stdout()).contains("Project-FM-redacted.xlsx");
        assertThat(redact.stderr()).contains("redact report written to");

        Path output = outputDir.resolve("Project-FM-redacted.xlsx");
        Path report = outputDir.resolve("Project-FM-redact-report.json");
        assertThat(output).exists();
        assertThat(report).exists();
        assertThat(Files.readString(report)).contains("\"coord\" : \"B2\"")
                .contains("\"original\" : \"1000000");

        try (XSSFWorkbook workbook = new XSSFWorkbook(output.toFile())) {
            Sheet sheet = workbook.getSheet("Assumptions");
            assertThat(sheet).isNotNull();

            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Revenue");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Growth rate");

            double revenue = sheet.getRow(1).getCell(0).getNumericCellValue();
            assertThat(revenue).isNotEqualTo(1_000_000.0);

            Cell formulaCell = sheet.getRow(2).getCell(0);
            assertThat(formulaCell.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(formulaCell.getCellFormula()).isEqualTo("B2*0.12");

            String amount = sheet.getRow(3).getCell(0).getStringCellValue();
            assertThat(amount).startsWith("₹");
            assertThat(amount).doesNotContain("10,00,000");

            double mergedAnchor = sheet.getRow(4).getCell(0).getNumericCellValue();
            assertThat(mergedAnchor).isNotEqualTo(500.0);
            assertThat(sheet.getRow(5).getCell(1).getNumericCellValue()).isEqualTo(999.0);
        }
    }

    @Test
    void redactWithoutPriorIngest_autoIngestsThenRedacts() throws Exception {
        Path input = writeRedactFixture();
        Path db = tempDir.resolve("empty.db");
        Path outputDir = tempDir.resolve("redacted");

        RunResult redact = run("redact",
                "--input", input.toString(),
                "--db", db.toString(),
                "--mandate-id", "1",
                "--sheet", "Assumptions",
                "--output-dir", outputDir.toString());

        assertThat(redact.exitCode()).isZero();
        assertThat(redact.stdout()).contains("Auto-ingested");
        assertThat(redact.stdout()).contains("Redacted sheet 'Assumptions'");
        assertThat(outputDir.resolve("Project-FM-redacted.xlsx")).exists();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (java.sql.ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM parse_run")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void redactWhenAlreadyIngested_skipsAutoIngest() throws Exception {
        Path input = writeRedactFixture();
        Path db = tempDir.resolve("workspace.db");
        Path outputDir = tempDir.resolve("redacted");

        run("ingest", "--input", input.toString(), "--mandate-id", "1", "--db", db.toString());
        RunResult redact = run("redact",
                "--input", input.toString(),
                "--db", db.toString(),
                "--mandate-id", "1",
                "--sheet", "Assumptions",
                "--output-dir", outputDir.toString());

        assertThat(redact.exitCode()).isZero();
        assertThat(redact.stdout()).doesNotContain("Auto-ingested");
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (java.sql.ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM parse_run")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void redactUnknownSheet_exits3() throws Exception {
        Path input = writeRedactFixture();
        Path db = tempDir.resolve("workspace.db");
        Path outputDir = tempDir.resolve("redacted");

        run("ingest", "--input", input.toString(), "--mandate-id", "1", "--db", db.toString());

        RunResult redact = run("redact",
                "--input", input.toString(),
                "--db", db.toString(),
                "--mandate-id", "1",
                "--sheet", "Missing",
                "--output-dir", outputDir.toString());

        assertThat(redact.exitCode()).isEqualTo(3);
        assertThat(redact.stderr()).contains("sheet not found");
    }

    @Test
    void redactRefreshesFormulaCachedValuesForSameSheetRefs() throws Exception {
        Path input;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Assumptions");
            Row row = sheet.createRow(17);
            row.createCell(3).setCellValue(0.4);
            row.createCell(4).setCellFormula("D18");

            input = tempDir.resolve("formula-cache.xlsx");
            try (FileOutputStream out = new FileOutputStream(input.toFile())) {
                workbook.write(out);
            }
        }

        Path db = tempDir.resolve("workspace.db");
        Path outputDir = tempDir.resolve("redacted");
        run("ingest", "--input", input.toString(), "--mandate-id", "1", "--db", db.toString());
        run("redact", "--input", input.toString(), "--db", db.toString(),
                "--mandate-id", "1", "--sheet", "Assumptions", "--output-dir", outputDir.toString());

        Path output = outputDir.resolve("formula-cache-redacted.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(output.toFile())) {
            Sheet sheet = workbook.getSheet("Assumptions");
            Cell d18 = sheet.getRow(17).getCell(3);
            Cell e18 = sheet.getRow(17).getCell(4);

            assertThat(d18.getNumericCellValue()).isNotEqualTo(0.4);
            assertThat(e18.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(e18.getCellFormula()).isEqualTo("D18");
            assertThat(e18.getNumericCellValue()).isEqualTo(d18.getNumericCellValue());
        }
    }

    @Test
    void redactAllSheets_processesEveryTab() throws Exception {
        Path input;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheetA = workbook.createSheet("TabA");
            sheetA.createRow(0).createCell(0).setCellValue(100.0);
            Sheet sheetB = workbook.createSheet("TabB");
            sheetB.createRow(0).createCell(0).setCellValue(200.0);

            input = tempDir.resolve("all-sheets.xlsx");
            try (FileOutputStream out = new FileOutputStream(input.toFile())) {
                workbook.write(out);
            }
        }

        Path db = tempDir.resolve("workspace.db");
        Path outputDir = tempDir.resolve("redacted");
        run("ingest", "--input", input.toString(), "--mandate-id", "1", "--db", db.toString());

        RunResult redact = run("redact", "--input", input.toString(), "--db", db.toString(),
                "--mandate-id", "1", "--all-sheets", "--output-dir", outputDir.toString());

        assertThat(redact.exitCode()).isZero();
        assertThat(redact.stdout()).contains("Redacted 2 sheets").contains("2 cells replaced");

        Path output = outputDir.resolve("all-sheets-redacted.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(output.toFile())) {
            assertThat(workbook.getSheet("TabA").getRow(0).getCell(0).getNumericCellValue())
                    .isNotEqualTo(100.0);
            assertThat(workbook.getSheet("TabB").getRow(0).getCell(0).getNumericCellValue())
                    .isNotEqualTo(200.0);
        }
    }

    @Test
    void missingSheetAndAllSheets_exits2() {
        RunResult result = run("redact", "--input", "file.xlsx", "--mandate-id", "1",
                "--db", "ws.db", "--output-dir", tempDir.toString());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("--sheet is required unless --all-sheets");
    }

    @Test
    void sheetAndAllSheetsTogether_exits2() throws Exception {
        Path input = writeRedactFixture();
        Path db = tempDir.resolve("workspace.db");

        RunResult result = run("redact", "--input", input.toString(), "--db", db.toString(),
                "--mandate-id", "1", "--sheet", "Assumptions", "--all-sheets",
                "--output-dir", tempDir.resolve("redacted").toString());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("mutually exclusive");
    }

    @Test
    void redactPreservesHardcodedZeros() throws Exception {
        Path input;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Assumptions");
            sheet.createRow(0).createCell(0).setCellValue(0.0);
            sheet.createRow(1).createCell(0).setCellValue(42.0);
            sheet.createRow(2).createCell(0).setCellValue("0");
            sheet.createRow(3).createCell(0).setCellValue("₹0");

            input = tempDir.resolve("zeros.xlsx");
            try (FileOutputStream out = new FileOutputStream(input.toFile())) {
                workbook.write(out);
            }
        }

        Path db = tempDir.resolve("workspace.db");
        Path outputDir = tempDir.resolve("redacted");
        run("ingest", "--input", input.toString(), "--mandate-id", "1", "--db", db.toString());
        run("redact", "--input", input.toString(), "--db", db.toString(),
                "--mandate-id", "1", "--sheet", "Assumptions", "--output-dir", outputDir.toString());

        Path output = outputDir.resolve("zeros-redacted.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(output.toFile())) {
            Sheet sheet = workbook.getSheet("Assumptions");
            assertThat(sheet.getRow(0).getCell(0).getNumericCellValue()).isZero();
            assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isNotEqualTo(42.0);
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("0");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("₹0");
        }
    }
}
