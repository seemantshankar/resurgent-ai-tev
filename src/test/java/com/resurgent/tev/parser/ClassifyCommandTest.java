package com.resurgent.tev.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.classify.ClassifierLlm;
import com.resurgent.tev.parser.classify.LayerAJudgment;
import com.resurgent.tev.parser.classify.LayerAPrompt;
import com.resurgent.tev.parser.classify.LayerBLineJudgment;
import com.resurgent.tev.parser.classify.LayerBPrompt;
import com.resurgent.tev.parser.classify.Relevance;
import com.resurgent.tev.parser.classify.ScheduleFamily;
import com.resurgent.tev.parser.classify.Triage;
import com.resurgent.tev.parser.cli.ClassifyCommand;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import com.resurgent.tev.parser.discover.DiscoverService;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** CLI wiring for {@code tev-parse classify}. */
class ClassifyCommandTest {

    @TempDir
    Path tempDir;

    private record RunResult(int exitCode, String stdout, String stderr) {}

    private RunResult run(CommandLine commandLine, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = commandLine
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
        return new RunResult(exit, out.toString(), err.toString());
    }

    private RunResult runMain(String... args) {
        return run(Main.commandLine(), args);
    }

    @Test
    void classifyPrintsDispositionCountsWithoutPacketDump() throws Exception {
        Path xlsx = tempDir.resolve("cli.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("Civil");
            row.createCell(1).setCellValue(100.0);
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("cli.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        ClassifierLlm fake = new ClassifierLlm() {
            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                return new LayerAJudgment(
                        ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                        List.of(), List.of(), null);
            }

            @Override
            public List<LayerBLineJudgment> classifyLayerB(LayerBPrompt prompt) {
                return List.of();
            }
        };
        CommandLine commandLine = new CommandLine(new ClassifyCommand(fake));
        RunResult result = run(commandLine,
                "--db", db.toString(),
                "--parse-run", Long.toString(ingest.parseRunId()));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("dispositions").contains("coverage parents");
        assertThat(result.stdout()).doesNotContain("\"core\"").doesNotContain("{");
    }

    @Test
    void missingParseRunExitsThree() throws Exception {
        Path db = tempDir.resolve("empty.db");
        try (var ignored = com.resurgent.tev.parser.db.WorkspaceDatabase.open(db)) {
            // schema only
        }
        RunResult result = runMain("classify", "--db", db.toString(), "--parse-run", "42");
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderr()).contains("parse run");
    }

    @Test
    void missingDatabaseExitsThree() {
        Path missing = tempDir.resolve("absent.db");
        RunResult result = runMain("classify", "--db", missing.toString(), "--parse-run", "1");
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderr()).contains("database");
    }

    @Test
    void missingRequiredOptionsExitsTwo() {
        RunResult result = runMain("classify");
        assertThat(result.exitCode()).isEqualTo(2);
    }
}
