package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
     * Seam: {@link ClassifyService} scheduling for #122 — coverage-parent
     * concurrency and deadline abort without a live LLM.
     */
class ClassifySchedulingTest {

    @TempDir
    Path tempDir;

    @Test
    @Timeout(15)
    void coverageParentsOnSeparateSheetsOverlapInFlight() throws Exception {
        Path xlsx = twoSheetCosts("overlap.xlsx");
        Path db = tempDir.resolve("overlap.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        CountDownLatch parentsStarted = new CountDownLatch(2);
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm() {
            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                if (prompt.cheapPass()) {
                    parentsStarted.countDown();
                    try {
                        if (!parentsStarted.await(4, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "coverage parents did not overlap in flight");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted waiting for peer parent", e);
                    }
                }
                return super.classifyLayerA(prompt);
            }
        };

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());
        assertThat(parentsStarted.getCount()).isZero();
        assertThat(summary.coverageParentCount()).isEqualTo(2);
        assertThat(summary.dispositionCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Timeout(15)
    void namingQuestionsStartAfterAllLayerAFinish() throws Exception {
        Path xlsx = parallelBands("pipeline.xlsx");
        Path db = tempDir.resolve("pipeline.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        AtomicInteger childAStarted = new AtomicInteger();
        AtomicLong slowAFinishedNanos = new AtomicLong(Long.MAX_VALUE);
        AtomicLong firstBStartedNanos = new AtomicLong(0L);
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm() {
            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                LayerAJudgment judgment = super.classifyLayerA(prompt);
                if (!prompt.cheapPass()) {
                    if (childAStarted.incrementAndGet() == 2) {
                        sleepQuietly(400);
                        slowAFinishedNanos.set(System.nanoTime());
                    }
                }
                return judgment;
            }

            @Override
            public List<LayerBLineJudgment> classifyLayerB(LayerBPrompt prompt) {
                firstBStartedNanos.compareAndSet(0L, System.nanoTime());
                return super.classifyLayerB(prompt);
            }
        };
        llm.bindFirstAmountAsCivilAdd = true;

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());
        assertThat(childAStarted.get()).isGreaterThanOrEqualTo(2);
        assertThat(summary.bindingCount()).isGreaterThanOrEqualTo(1);
        assertThat(firstBStartedNanos.get()).isPositive();
        assertThat(firstBStartedNanos.get())
                .as("naming questions start after Layer A, not pipelined per packet")
                .isGreaterThan(slowAFinishedNanos.get());
    }

    @Test
    @Timeout(15)
    void childrenStartWhenTheirCoverageParentFinishesWithoutWaitingForOtherSheets()
            throws Exception {
        Path xlsx = twoSheetCosts("parent-ready.xlsx");
        Path db = tempDir.resolve("parent-ready.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        AtomicInteger parentsStarted = new AtomicInteger();
        AtomicLong slowParentFinishedNanos = new AtomicLong(Long.MAX_VALUE);
        AtomicLong firstChildStartedNanos = new AtomicLong(0L);
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm() {
            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                if (prompt.cheapPass()) {
                    if (parentsStarted.incrementAndGet() == 2) {
                        sleepQuietly(400);
                        slowParentFinishedNanos.set(System.nanoTime());
                    }
                } else {
                    firstChildStartedNanos.compareAndSet(0L, System.nanoTime());
                }
                return super.classifyLayerA(prompt);
            }
        };

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());
        assertThat(summary.coverageParentCount()).isEqualTo(2);
        assertThat(firstChildStartedNanos.get()).isPositive();
        assertThat(firstChildStartedNanos.get())
                .as("children must start when their parent is ready, not after every sheet's parent")
                .isLessThan(slowParentFinishedNanos.get());
    }

    @Test
    @Timeout(15)
    void attemptDeadlineAbortsWithoutWritingAndLeavesPriorSnapshotIntact() throws Exception {
        Path xlsx = twoSheetCosts("deadline.xlsx");
        Path db = tempDir.resolve("deadline.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        ClassifyServiceTest.FakeClassifierLlm first = new ClassifyServiceTest.FakeClassifierLlm();
        first.judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                List.of(), List.of(), null);
        new ClassifyService(first).classify(db, ingest.parseRunId());

        ClassifyServiceTest.FakeClassifierLlm slow = new ClassifyServiceTest.FakeClassifierLlm() {
            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                sleepQuietly(2_000);
                return super.classifyLayerA(prompt);
            }
        };
        slow.judgment = new LayerAJudgment(
                ScheduleFamily.ASSUMPTIONS, Triage.SCRATCH, Relevance.NOISE,
                List.of(), List.of(), null);

        ClassifyLimits tight = new ClassifyLimits(
                8, Duration.ofMillis(150), Duration.ofSeconds(5));
        assertThatThrownBy(() -> new ClassifyService(slow, new DiscoverService(), tight)
                        .classify(db, ingest.parseRunId()))
                .isInstanceOf(ClassifyException.class)
                .hasMessageContaining("incomplete");

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            var rows = repo.selectPacketDispositionsForParseRun(ingest.parseRunId());
            assertThat(rows).isNotEmpty();
            assertThat(rows).allMatch(row -> ScheduleFamily.CAPEX_DETAIL.equals(row.scheduleFamily()));
            assertThat(rows).noneMatch(row -> ScheduleFamily.ASSUMPTIONS.equals(row.scheduleFamily()));
        }
    }

    @Test
    @Timeout(15)
    void layerBAttemptDeadlineDropsThatCallAndKeepsTheRun() throws Exception {
        Path xlsx = twoSheetCosts("layer-b-deadline.xlsx");
        Path db = tempDir.resolve("layer-b-deadline.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        ClassifyServiceTest.FakeClassifierLlm slow = new ClassifyServiceTest.FakeClassifierLlm() {
            @Override
            public List<LayerBLineJudgment> classifyLayerB(LayerBPrompt prompt) {
                sleepQuietly(2_000);
                return super.classifyLayerB(prompt);
            }
        };
        slow.judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                List.of(), List.of(), null);

        ClassifyLimits tight = new ClassifyLimits(
                8, Duration.ofMillis(300), Duration.ofSeconds(10));
        ClassifySummary summary = new ClassifyService(slow, new DiscoverService(), tight)
                .classify(db, ingest.parseRunId());

        assertThat(summary.layerBStats().failedCalls()).isPositive();
        assertThat(summary.layerBStats().failedCallSamples())
                .anyMatch(sample -> sample.contains("exceeded attempt deadline"));
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectPacketDispositionsForParseRun(ingest.parseRunId()))
                    .as("Layer A dispositions survive a dropped Layer B call")
                    .isNotEmpty();
        }
    }

    @Test
    @Timeout(15)
    void classifyDeadlineAbortsAsIncompleteWithoutWriting() throws Exception {
        Path xlsx = twoSheetCosts("classify-deadline.xlsx");
        Path db = tempDir.resolve("classify-deadline.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        ClassifyServiceTest.FakeClassifierLlm first = new ClassifyServiceTest.FakeClassifierLlm();
        first.judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                List.of(), List.of(), null);
        new ClassifyService(first).classify(db, ingest.parseRunId());

        ClassifyServiceTest.FakeClassifierLlm slow = new ClassifyServiceTest.FakeClassifierLlm() {
            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                sleepQuietly(2_000);
                return super.classifyLayerA(prompt);
            }
        };
        slow.judgment = new LayerAJudgment(
                ScheduleFamily.ASSUMPTIONS, Triage.SCRATCH, Relevance.NOISE,
                List.of(), List.of(), null);

        ClassifyLimits tight = new ClassifyLimits(
                8, Duration.ofSeconds(30), Duration.ofMillis(200));
        assertThatThrownBy(() -> new ClassifyService(slow, new DiscoverService(), tight)
                        .classify(db, ingest.parseRunId()))
                .isInstanceOf(ClassifyException.class)
                .hasMessageContaining("incomplete")
                .hasMessageContaining("classify deadline");

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            var rows = repo.selectPacketDispositionsForParseRun(ingest.parseRunId());
            assertThat(rows).isNotEmpty();
            assertThat(rows).allMatch(row -> ScheduleFamily.CAPEX_DETAIL.equals(row.scheduleFamily()));
        }
    }

    private Path twoSheetCosts(String name) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (String sheetName : List.of("ASSETS", "CAPITAL")) {
                Sheet sheet = workbook.createSheet(sheetName);
                for (int r = 0; r < 3; r++) {
                    Row row = sheet.createRow(r);
                    row.createCell(0).setCellValue(sheetName + "L" + r);
                    row.createCell(1).setCellValue(10.0 + r);
                    row.createCell(4).setCellValue(sheetName + "R" + r);
                    row.createCell(5).setCellValue(20.0 + r);
                }
            }
            return writeWorkbook(workbook, name);
        }
    }

    private Path parallelBands(String name) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Parallel");
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(10.0 + r);
                row.createCell(4).setCellValue("R" + r);
                row.createCell(5).setCellValue(20.0 + r);
            }
            return writeWorkbook(workbook, name);
        }
    }

    private Path writeWorkbook(XSSFWorkbook workbook, String name) throws Exception {
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        workbook.close();
        return file;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
