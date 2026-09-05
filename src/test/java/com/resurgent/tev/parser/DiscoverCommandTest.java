package com.resurgent.tev.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** CLI wiring for {@code tev-parse discover}. */
class DiscoverCommandTest {

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

    @Test
    void discoverPrintsCountsWithoutPacketJson() throws Exception {
        Path xlsx = tempDir.resolve("cli.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("a");
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("cli.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);

        RunResult result = run("discover",
                "--db", db.toString(),
                "--parse-run", Long.toString(ingest.parseRunId()));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("worksheets").contains("candidates");
        assertThat(result.stdout()).doesNotContain("\"core\"").doesNotContain("Packet");
        assertThat(result.stdout()).doesNotContain("{");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM candidate WHERE candidate_kind = 'coverage_parent'")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(1);
        }
    }

    @Test
    void missingParseRunExitsThree() throws Exception {
        Path db = tempDir.resolve("empty.db");
        try (var ignored = com.resurgent.tev.parser.db.WorkspaceDatabase.open(db)) {
            // schema only
        }
        RunResult result = run("discover", "--db", db.toString(), "--parse-run", "42");
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderr()).contains("parse run");
    }

    @Test
    void missingDatabaseExitsThree() {
        Path missing = tempDir.resolve("absent.db");
        RunResult result = run("discover", "--db", missing.toString(), "--parse-run", "1");
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderr()).contains("database");
    }

    @Test
    void missingRequiredOptionsExitsTwo() {
        RunResult result = run("discover");
        assertThat(result.exitCode()).isEqualTo(2);
    }
}
