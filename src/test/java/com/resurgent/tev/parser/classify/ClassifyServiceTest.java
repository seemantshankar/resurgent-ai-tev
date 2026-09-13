package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam: {@link ClassifyService} — Layer A disposition from derived Packets plus
 * a fake LLM, without rewriting Candidate geometry (#106).
 */
class ClassifyServiceTest {

    @TempDir
    Path tempDir;

    private Path writeWorkbook(XSSFWorkbook workbook, String name) throws Exception {
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        workbook.close();
        return file;
    }

    private Path costScheduleWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            Row body = sheet.createRow(1);
            body.createCell(0).setCellValue("Civil Works");
            body.createCell(1).setCellValue(100.0);
            return writeWorkbook(workbook, "costs.xlsx");
        }
    }

    @Test
    void classifyPersistsLayerADispositionForEachCandidate() throws Exception {
        Path xlsx = costScheduleWorkbook();
        Path db = tempDir.resolve("classify.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL,
                Triage.MAIN,
                Relevance.PRIMARY,
                List.of("Item"),
                List.of("Amount"),
                "Project Cost");

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(summary.parseRunId()).isEqualTo(ingest.parseRunId());
        assertThat(summary.dispositionCount()).isGreaterThanOrEqualTo(1);
        assertThat(llm.prompts).isNotEmpty();
        assertThat(llm.prompts.get(0).ontologySlice().node("Project Cost")).isPresent();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            List<PacketDisposition> rows = repo.selectPacketDispositionsForParseRun(
                    ingest.parseRunId());
            assertThat(rows).hasSize(candidates.size());
            assertThat(rows).allMatch(row -> ScheduleFamily.CAPEX_DETAIL.equals(row.scheduleFamily()));
            assertThat(rows).allMatch(row -> Triage.MAIN.equals(row.triage()));
            assertThat(rows).allMatch(row -> Relevance.PRIMARY.equals(row.relevance()));
            assertThat(rows.get(0).rowLabels()).contains("Item");
            assertThat(rows.get(0).columnHeaders()).contains("Amount");
            assertThat(rows.get(0).packetDefaultHead()).isEqualTo("Project Cost");
        }
    }

    @Test
    void sentPacketsAreNumberRedactedAndAmountsStayOnTheCellGraph() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Parallel");
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(r == 0 ? 9_998_887.0 : 10.0 + r);
                row.createCell(4).setCellValue("R" + r);
                row.createCell(5).setCellValue(20.0 + r);
            }
            xlsx = writeWorkbook(workbook, "redact.xlsx");
        }
        Path db = tempDir.resolve("redact.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(llm.prompts).isNotEmpty();
        assertThat(llm.prompts.stream()
                .flatMap(prompt -> prompt.packet().cells().stream())
                .anyMatch(cell -> cell.numericValue() != null)).isTrue();
        for (LayerAPrompt prompt : llm.prompts) {
            for (PacketCell cell : prompt.packet().cells()) {
                assertThat(nullToEmpty(cell.numericValue())).doesNotContain("9998887");
                assertThat(nullToEmpty(cell.displayValue())).doesNotContain("9998887");
                assertThat(nullToEmpty(cell.textValue())).doesNotContain("9998887");
            }
        }

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                var rs = workspace.connection().createStatement().executeQuery(
                        "SELECT numeric_value FROM cell WHERE numeric_value IS NOT NULL")) {
            boolean found = false;
            while (rs.next()) {
                if (rs.getString(1).contains("9998887")) {
                    found = true;
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    void coverageParentCheapPassRunsBeforeChildrenWithParentContext() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Parallel");
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(10.0 + r);
                row.createCell(4).setCellValue("R" + r);
                row.createCell(5).setCellValue(20.0 + r);
            }
            xlsx = writeWorkbook(workbook, "parallel.xlsx");
        }
        Path db = tempDir.resolve("parent-context.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.SUPPORTING,
                List.of(), List.of(), null);
        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(summary.coverageParentCount()).isEqualTo(1);
        assertThat(llm.prompts.size()).isGreaterThan(1);
        LayerAPrompt first = llm.prompts.get(0);
        assertThat(first.cheapPass()).isTrue();
        assertThat(first.packet().candidateKind()).isEqualTo("coverage_parent");
        assertThat(first.parentDisposition()).isNull();
        assertThat(first.packet().cells())
                .noneMatch(cell -> "number".equals(cell.valueType()));

        List<LayerAPrompt> children = llm.prompts.stream().skip(1).toList();
        assertThat(children).isNotEmpty();
        assertThat(children).allMatch(prompt -> !prompt.cheapPass());
        assertThat(children).allMatch(prompt -> prompt.parentDisposition() != null);
        assertThat(children).allMatch(prompt ->
                ScheduleFamily.CAPEX_DETAIL.equals(prompt.parentDisposition().scheduleFamily()));

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<PacketDisposition> rows = repo.selectPacketDispositionsForParseRun(
                    ingest.parseRunId());
            assertThat(rows.stream().filter(PacketDisposition::cheapPass)).hasSize(1);
            assertThat(rows.stream().filter(row -> !row.cheapPass())).isNotEmpty();
            assertThat(rows.stream().filter(row -> !row.cheapPass()))
                    .allMatch(row -> row.parentCandidateId() != null);
        }
    }

    @Test
    void reclassifyReplacesLayerAWithoutRewritingCandidateGeometry() throws Exception {
        Path xlsx = costScheduleWorkbook();
        Path db = tempDir.resolve("reclassify.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        List<CandidateRow> before;
        List<Long> memberIds;
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            before = List.copyOf(repo.selectCandidatesForParseRun(ingest.parseRunId()));
            memberIds = repo.selectCandidateMemberCellIds(before.get(0).candidateId());
        }

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                List.of(), List.of(), null);
        new ClassifyService(llm).classify(db, ingest.parseRunId());

        llm.judgment = new LayerAJudgment(
                ScheduleFamily.ASSUMPTIONS, Triage.SCRATCH, Relevance.NOISE,
                List.of(), List.of(), null);
        new ClassifyService(llm).classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> after = repo.selectCandidatesForParseRun(ingest.parseRunId());
            assertThat(after).isEqualTo(before);
            assertThat(repo.selectCandidateMemberCellIds(before.get(0).candidateId()))
                    .isEqualTo(memberIds);
            List<PacketDisposition> rows = repo.selectPacketDispositionsForParseRun(
                    ingest.parseRunId());
            assertThat(rows).hasSize(before.size());
            assertThat(rows).allMatch(row -> ScheduleFamily.ASSUMPTIONS.equals(row.scheduleFamily()));
            assertThat(rows).allMatch(row -> Triage.SCRATCH.equals(row.triage()));
            assertThat(rows).allMatch(row -> Relevance.NOISE.equals(row.relevance()));
        }
    }

    /** Scripted LLM for tests: records prompts and returns a fixed Layer A judgment. */
    static final class FakeClassifierLlm implements ClassifierLlm {
        final List<LayerAPrompt> prompts = new ArrayList<>();
        LayerAJudgment judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                List.of(), List.of(), null);

        @Override
        public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
            prompts.add(prompt);
            return judgment;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
