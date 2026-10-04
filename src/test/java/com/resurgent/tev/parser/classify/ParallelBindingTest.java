package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One slow model call must not hold up the binding of every other region. */
class ParallelBindingTest {

    @TempDir
    Path tempDir;

    @Test
    void regionsAreBoundConcurrentlyAndEveryOneStillGetsItsBinding() throws Exception {
        Path xlsx = tempDir.resolve("two.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (String name : new String[] {"ONE", "TWO", "THREE"}) {
                Sheet sheet = workbook.createSheet(name);
                Row row = sheet.createRow(0);
                row.createCell(0).setCellValue("Establishment Cost");
                row.createCell(1).setCellValue(500_000);
                Row second = sheet.createRow(1);
                second.createCell(0).setCellValue("Sundry");
                second.createCell(1).setCellValue(50_000);
            }
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("two.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 31L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        AtomicInteger asked = new AtomicInteger();
        ClassifierLlm llm = new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of(new RegionProposal("main", "A1:B2", "schedule", "unit-test region"));
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                return new LayerAJudgment(
                        ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                        List.of("Establishment Cost", "Sundry"), List.of(), null, "Expense schedule.");
            }

            @Override
            public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
                asked.incrementAndGet();
                mostAtOnce.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
                return List.of(
                        new LayerBAssignment(1, null, "economic", "Project Cost > Preliminary & Pre-operative Expenses"),
                        new LayerBAssignment(2, null, "economic", "Project Cost > Preliminary & Pre-operative Expenses"));
            }
        };

        ClassifyService service = new ClassifyService(llm).withTuning(new ClassifyTuning(50, 25, 3));
        service.classify(db, ingest.parseRunId());
        BindSummary bound = service.bindSheets(db, ingest.parseRunId(), List.of("ONE", "TWO", "THREE"));

        assertThat(asked.get()).isGreaterThanOrEqualTo(3);
        assertThat(mostAtOnce.get()).as("regions asked at the same time (asked=" + asked.get() + ")").isGreaterThan(1);
        assertThat(bound.boundCells()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void theRetryForCellsStillUnboundAlsoRunsAcrossRegionsAtOnce() throws Exception {
        Path xlsx = tempDir.resolve("retry.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (String name : new String[] {"ONE", "TWO", "THREE"}) {
                Sheet sheet = workbook.createSheet(name);
                Row row = sheet.createRow(0);
                row.createCell(0).setCellValue("Establishment Cost");
                row.createCell(1).setCellValue(500_000);
                Row second = sheet.createRow(1);
                second.createCell(0).setCellValue("Sundry");
                second.createCell(1).setCellValue(50_000);
            }
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("retry.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 32L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        AtomicInteger retriesInFlight = new AtomicInteger();
        AtomicInteger mostRetriesAtOnce = new AtomicInteger();
        ClassifierLlm llm = new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of(new RegionProposal("main", "A1:B2", "schedule", "unit-test region"));
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                return new LayerAJudgment(
                        ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                        List.of("Establishment Cost", "Sundry"), List.of(), null, "Expense schedule.");
            }

            @Override
            public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
                if (!prompt.retry()) {
                    return List.of(); // the first question binds nothing, so every region needs the retry
                }
                mostRetriesAtOnce.accumulateAndGet(retriesInFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    retriesInFlight.decrementAndGet();
                }
                return List.of(
                        new LayerBAssignment(1, null, "economic", "Project Cost > Preliminary & Pre-operative Expenses"),
                        new LayerBAssignment(2, null, "economic", "Project Cost > Preliminary & Pre-operative Expenses"));
            }
        };

        ClassifyService service = new ClassifyService(llm).withTuning(new ClassifyTuning(50, 25, 3));
        service.classify(db, ingest.parseRunId());
        BindSummary bound = service.bindSheets(db, ingest.parseRunId(), List.of("ONE", "TWO", "THREE"));

        assertThat(mostRetriesAtOnce.get()).as("regions retried at the same time").isGreaterThan(1);
        assertThat(bound.boundCells()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void aSmallBindingPromptGetsTheFailFastDeadlineNotTheBigBatchOne() {
        int maxTokens = OpenRouterClassifierLlm.bindMaxCompletionTokens(3_000);

        assertThat(OpenRouterClassifierLlm.Deadlines.FAIL_FAST.forRequest(3_000, maxTokens))
                .isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void aBigBindingPromptKeepsRoomForALongAnswerAndTheLongDeadline() {
        int maxTokens = OpenRouterClassifierLlm.bindMaxCompletionTokens(80_000);

        assertThat(maxTokens).isGreaterThanOrEqualTo(16_384);
        assertThat(OpenRouterClassifierLlm.Deadlines.FAIL_FAST.forRequest(80_000, maxTokens))
                .isEqualTo(Duration.ofSeconds(180));
    }
}
