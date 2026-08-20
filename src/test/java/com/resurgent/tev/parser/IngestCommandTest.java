package com.resurgent.tev.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
