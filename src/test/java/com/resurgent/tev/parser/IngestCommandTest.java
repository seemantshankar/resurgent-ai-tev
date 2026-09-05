package com.resurgent.tev.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.resurgent.tev.parser.db.LegacyWorkspaceFactory;

import picocli.CommandLine;

/**
 * Primary-seam test: drives the real Picocli command in-process via
 * {@code CommandLine.execute(...)} against a temporary SQLite DB.
 * Asserts external behavior only: exit codes, stdout/stderr, DB contents.
 */
class IngestCommandTest {

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

    private Path writeCsv(String name, String content) throws Exception {
        Path csv = tempDir.resolve(name);
        Files.writeString(csv, content);
        return csv;
    }

    private Path writeBytes(String name, byte[] content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file;
    }

    private Path writeXlsx(String name) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("a");
            row.createCell(1).setCellValue(1.0);
            Path file = tempDir.resolve(name);
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
            return file;
        }
    }

    private Path writeXls(String name) throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Legacy");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("a");
            row.createCell(1).setCellValue(1.0);
            Path file = tempDir.resolve(name);
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
            return file;
        }
    }

    private long count(Connection c, String table) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.getLong(1);
        }
    }

    @Test
    void ingestWellFormedCsv_exitsZeroAndLandsFullGraph() throws Exception {
        Path csv = writeCsv("simple.csv", "a,b,c\n1,2,3\nx,y,z\n");
        Path db = tempDir.resolve("ws.db");

        RunResult result = run("ingest", "--input", csv.toString(),
                "--mandate-id", "1", "--db", db.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("simple.csv").contains("9 cells");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(count(c, "source_file")).isEqualTo(1);
            assertThat(count(c, "parse_run")).isEqualTo(1);
            assertThat(count(c, "worksheet")).isEqualTo(1);
            assertThat(count(c, "cell")).isEqualTo(9);

            try (ResultSet rs = c.createStatement().executeQuery("SELECT sheet_name FROM worksheet")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("simple");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT coord, row_num, col_num, raw_value FROM cell WHERE row_num = 2 AND col_num = 3")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("coord")).isEqualTo("C2");
                assertThat(rs.getString("raw_value")).isEqualTo("3");
            }
        }
    }

    @Test
    void ingestUtf16LeTabDelimited_recordsDialectAndParsesCells() throws Exception {
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        byte[] body = "h1\th2\th3\n1\t2\t3\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] content = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, content, 0, bom.length);
        System.arraycopy(body, 0, content, bom.length, body.length);
        Path csv = writeBytes("utf16le.tsv", content);
        Path db = tempDir.resolve("ws.db");

        RunResult result = run("ingest", "--input", csv.toString(),
                "--mandate-id", "1", "--db", db.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("utf16le.tsv").contains("6 cells");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(count(c, "cell")).isEqualTo(6);

            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT raw_value FROM cell WHERE row_num = 2 AND col_num = 3")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("3");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT raw_metadata FROM source_file")) {
                assertThat(rs.next()).isTrue();
                String rawMetadata = rs.getString(1);
                assertThat(rawMetadata).contains("UTF-16LE");
                assertThat(rawMetadata).contains("\"hasBom\":true");
                assertThat(rawMetadata).contains("\"detectedBy\":\"bom\"");
                assertThat(rawMetadata).contains("\"delimiter\":\"\\t\"");
            }
        }
    }

    @Test
    void missingRequiredOptions_exits2WithUsageOnStderr() {
        RunResult result = run("ingest", "--mandate-id", "1");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("--input").contains("--db");
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void malformedMandateId_exits2() {
        RunResult result = run("ingest", "--input", "x.csv",
                "--mandate-id", "abc", "--db", "ws.db");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void missingInputFile_exits1WithDiagnosticOnStderr() {
        Path db = tempDir.resolve("ws.db");

        RunResult result = run("ingest", "--input", tempDir.resolve("nope.csv").toString(),
                "--mandate-id", "1", "--db", db.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("nope.csv");
        assertThat(result.stdout()).isEmpty();
    }

    private Path writeConfig(String name, String content) throws Exception {
        Path config = tempDir.resolve(name);
        Files.writeString(config, content);
        return config;
    }

    private void assertConfigRejection(String configName, String configJson,
            String expectedDiagnostic) throws Exception {
        Path csv = writeCsv("simple.csv", "a,b\n1,2\n");
        Path db = tempDir.resolve(configName + ".db");
        Path config = writeConfig(configName + ".json", configJson);

        RunResult result = run("ingest", "--input", csv.toString(),
                "--mandate-id", "1", "--db", db.toString(), "--config", config.toString());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains(expectedDiagnostic);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void unknownConfigKey_exits2WithDiagnostic() throws Exception {
        assertConfigRejection("bad", "{\"unknownKey\": true}",
                "unknown config key: unknownKey");
    }

    @Test
    void configDisablingSecurityProtection_exits2() throws Exception {
        assertConfigRejection("insecure", "{\"rejectPasswordProtected\": false}",
                "security protection cannot be disabled");
    }

    @Test
    void configExceedingHardCeiling_exits2() throws Exception {
        assertConfigRejection("toobig", "{\"maxFileSizeBytes\": 9999999999999}",
                "exceeds hard ceiling");
    }

    @Test
    void xlsWithFlagOff_exits3AndWritesIngestRejection() throws Exception {
        Path xls = tempDir.resolve("legacy.xls");
        Files.write(xls, new byte[] {0x50}); // content is irrelevant; extension drives rejection
        Path db = tempDir.resolve("ws.db");

        RunResult result = run("ingest", "--input", xls.toString(),
                "--mandate-id", "1", "--db", db.toString());

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderr()).contains("xls intake is disabled");
        assertThat(result.stdout()).isEmpty();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(count(c, "ingest_rejection")).isEqualTo(1);
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT reason, detail FROM ingest_rejection")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("reason")).isEqualTo("xls_disabled");
                assertThat(rs.getString("detail")).contains("xls_disabled");
            }
        }
    }

    @Test
    void deterministicConfigHash_matchesForDefaultsAndDiffersOnOverride() throws Exception {
        Path csv = writeCsv("simple.csv", "a,b\n1,2\n");
        Path dbDefault = tempDir.resolve("ws-default.db");
        Path dbOverride = tempDir.resolve("ws-override.db");
        Path config = writeConfig("larger.json", "{\"maxSheetCount\": 60}");

        RunResult defaultResult = run("ingest", "--input", csv.toString(),
                "--mandate-id", "1", "--db", dbDefault.toString());
        RunResult overrideResult = run("ingest", "--input", csv.toString(),
                "--mandate-id", "1", "--db", dbOverride.toString(),
                "--config", config.toString());

        assertThat(defaultResult.exitCode()).isZero();
        assertThat(overrideResult.exitCode()).isZero();
        try (Connection c1 = DriverManager.getConnection("jdbc:sqlite:" + dbDefault);
                Connection c2 = DriverManager.getConnection("jdbc:sqlite:" + dbOverride)) {
            String hashDefault;
            String hashOverride;
            try (ResultSet rs = c1.createStatement().executeQuery(
                    "SELECT config_hash FROM parse_run")) {
                assertThat(rs.next()).isTrue();
                hashDefault = rs.getString(1);
            }
            try (ResultSet rs = c2.createStatement().executeQuery(
                    "SELECT config_hash FROM parse_run")) {
                assertThat(rs.next()).isTrue();
                hashOverride = rs.getString(1);
            }
            assertThat(hashDefault).isNotBlank();
            assertThat(hashOverride).isNotBlank();
            assertThat(hashDefault).isNotEqualTo(hashOverride);
        }
    }

    @Test
    void xlsWithFlagOn_ingestsLegacyWorkbook() throws Exception {
        Path xls = writeXls("legacy.xls");
        Path db = tempDir.resolve("xls-on.db");
        Path config = writeConfig("xls-on.json", "{\"xlsEnabled\": true}");

        RunResult result = run("ingest", "--input", xls.toString(),
                "--mandate-id", "1", "--db", db.toString(),
                "--config", config.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("legacy.xls").contains("2 cells");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(count(c, "source_file")).isEqualTo(1);
            assertThat(count(c, "parse_run")).isEqualTo(1);
            assertThat(count(c, "worksheet")).isEqualTo(1);
            assertThat(count(c, "cell")).isEqualTo(2);
        }
    }

    @Test
    void idempotentReingest_reportsExistingRun() throws Exception {
        Path xlsx = writeXlsx("dup.xlsx");
        Path db = tempDir.resolve("dup.db");

        RunResult first = run("ingest", "--input", xlsx.toString(),
                "--mandate-id", "1", "--db", db.toString());
        RunResult second = run("ingest", "--input", xlsx.toString(),
                "--mandate-id", "1", "--db", db.toString());

        assertThat(first.exitCode()).isZero();
        assertThat(first.stdout()).contains("Ingested");
        assertThat(second.exitCode()).isZero();
        assertThat(second.stdout()).contains("Reused existing parse run");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(count(c, "source_file")).isEqualTo(1);
            assertThat(count(c, "parse_run")).isEqualTo(1);
        }
    }

    @Test
    void ingestIntoPopulatedV10WithoutOptIn_exits1AndNamesDatabasePath() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        LegacyWorkspaceFactory.writePopulatedV10(db);
        Path csv = writeCsv("next.csv", "a,b\n1,2\n");

        RunResult result = run("ingest", "--input", csv.toString(),
                "--mandate-id", "1", "--db", db.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains(db.toAbsolutePath().normalize().toString());
        assertThat(result.stderr()).contains("parser-owned operational data");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(count(c, "cell")).isEqualTo(1);
            assertThat(count(c, "schema_migration")).isEqualTo(10);
        }
    }

    @Test
    void ingestIntoPopulatedV10WithOptIn_resetsThenIngests() throws Exception {
        Path db = tempDir.resolve("legacy-reset.db");
        LegacyWorkspaceFactory.writePopulatedV10(db);
        Path csv = writeCsv("next.csv", "a,b\n1,2\n");

        RunResult result = run("ingest", "--input", csv.toString(),
                "--mandate-id", "1", "--db", db.toString(),
                "--allow-destructive-reset");

        assertThat(result.exitCode()).isZero();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(count(c, "schema_migration")).isEqualTo(16);
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT coord FROM cell WHERE coord = 'Z9'")) {
                assertThat(rs.next()).isFalse();
            }
            assertThat(count(c, "cell")).isGreaterThan(0);
        }
    }
}
