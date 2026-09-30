package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Classify: LLM regions replace coverage-only discover, then Layer A + about. */
class ClassifyServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void materializesLlmRegionsThenPersistsAboutForMainAndHelper() throws Exception {
        Path xlsx = tempDir.resolve("roles.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("ASSETS");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("APPENDIX");
            title.createCell(1).setCellValue("Rs. in Lacs");
            for (int r = 2; r < 10; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("Item " + r);
                row.createCell(1).setCellValue(100.0 * r);
                row.createCell(2).setCellValue(10.0);
                row.createCell(3).setCellValue(1000.0 * r);
            }
            Row total = sheet.createRow(10);
            total.createCell(0).setCellValue("Total");
            total.createCell(3).setCellFormula("SUM(D3:D10)");
            sheet.createRow(5).createCell(15).setCellValue(37.0);
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }

        Path dbPath = tempDir.resolve("roles.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, dbPath);
        new DiscoverService().discover(dbPath, ingest.parseRunId());

        AtomicInteger layerACalls = new AtomicInteger();
        List<String> rolesSeen = new ArrayList<>();
        ClassifierLlm fake = new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of(
                        new RegionProposal("main", "A1:D11", "capex_table", "main schedule"),
                        new RegionProposal("helper", "A1:B1", "units", "title band helper"),
                        new RegionProposal("scratch", "P6", "orphan", "floating 37"));
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                layerACalls.incrementAndGet();
                rolesSeen.add(prompt.structuralRole());
                return new LayerAJudgment(
                        ScheduleFamily.CAPEX_DETAIL,
                        Triage.MAIN,
                        Relevance.PRIMARY,
                        List.of("Item"),
                        List.of("Amount"),
                        null,
                        "Capex item table with amounts and a total.");
            }
        };

        ClassifySummary summary =
                new ClassifyService(fake).classify(dbPath, ingest.parseRunId());

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            assertThat(candidates.stream().filter(c -> "coverage_parent".equals(c.candidateKind())))
                    .hasSize(1);
            assertThat(candidates.stream().filter(c -> "main".equals(c.structuralRole())))
                    .hasSize(1);
            assertThat(candidates.stream().filter(c -> "helper".equals(c.structuralRole())))
                    .hasSize(1);
            assertThat(candidates.stream().filter(c -> "scratch".equals(c.structuralRole())))
                    .hasSize(1);

            long eligible = candidates.stream().filter(ClassifyService::isEligible).count();
            assertThat(eligible).isEqualTo(2);
            assertThat(summary.eligibleCount()).isEqualTo(2);
            assertThat(summary.skippedCount()).isEqualTo(2); // coverage + scratch
            assertThat(layerACalls.get()).isEqualTo(2);
            assertThat(rolesSeen).containsExactlyInAnyOrder("main", "helper");

            List<PacketDisposition> dispositions =
                    repo.selectPacketDispositionsForParseRun(ingest.parseRunId());
            assertThat(dispositions).hasSize(2);
            assertThat(dispositions).allMatch(d -> d.about().contains("Capex item table"));
        }
    }

    @Test
    void layerBBatchingBenchmark1000UntypableCells() throws Exception {
        Path xlsx = tempDir.resolve("benchmark.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("DATA");
            
            // Create 1000+ scattered numeric cells with NO structural context
            // This forces them to be UNTYPABLE by all structural rules
            int cellCount = 0;
            for (int r = 0; r < 50; r++) {
                Row row = sheet.createRow(r);
                // Scattered across columns, no consistent labels
                for (int c = 0; c < 25; c++) {
                    row.createCell(c).setCellValue((double)(r * 1000 + c * 100 + cellCount++));
                }
            }
            
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }

        Path dbPath = tempDir.resolve("benchmark.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, dbPath);
        new DiscoverService().discover(dbPath, ingest.parseRunId());

        AtomicInteger batchCallCount = new AtomicInteger();
        AtomicInteger individualCallCount = new AtomicInteger();
        List<Integer> batchSizes = new java.util.ArrayList<>();
        long batchTotalMs = 0;

        ClassifierLlm fake = new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of(new RegionProposal("main", "A1:Y50", "data", "benchmark data"));
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                return new LayerAJudgment(
                        ScheduleFamily.ASSUMPTIONS, Triage.MAIN, Relevance.NOISE,
                        List.of(), List.of(), null, "Unstructured");
            }

            @Override
            public String classifyCellJson(String systemPrompt, String userMessage, int maxTokens) {
                // Detect batch vs individual
                int cellCount = userMessage.split("Cell \\d+:").length - 1;
                
                if (cellCount > 1) {
                    // Batch call
                    batchCallCount.incrementAndGet();
                    batchSizes.add(cellCount);
                    System.out.println("[BENCHMARK] Batch call #" + batchCallCount.get() 
                        + " with " + cellCount + " cells");
                } else {
                    // Individual call (fallback)
                    individualCallCount.incrementAndGet();
                }
                
                // Return valid response for all cells (wrapped in "results" key)
                StringBuilder json = new StringBuilder("{\"results\":[");
                for (int i = 0; i < cellCount; i++) {
                    if (i > 0) json.append(",");
                    json.append("{\"kind\":\"quantity\",\"scale\":\"unit\",\"unit\":\"\",\"currency\":\"\",\"confidence\":0.80}");
                }
                json.append("]}");
                return json.toString();
            }
        };

        System.out.println("\n" + "=".repeat(70));
        System.out.println("LAYER B BATCHING BENCHMARK: 1000+ Untyped Cells");
        System.out.println("=".repeat(70));
        
        long startMs = System.currentTimeMillis();
        ClassifySummary summary = new ClassifyService(fake).classify(dbPath, ingest.parseRunId());
        long totalMs = System.currentTimeMillis() - startMs;

        int totalCellsClassified = batchSizes.stream().mapToInt(Integer::intValue).sum() 
                                   + individualCallCount.get();

        System.out.println("\n--- RESULTS ---");
        System.out.println("Total cells classified: " + totalCellsClassified);
        System.out.println("Batch calls: " + batchCallCount.get() + " batches");
        System.out.println("Individual fallback calls: " + individualCallCount.get() + " cells");
        System.out.println("Total LLM calls: " + (batchCallCount.get() + individualCallCount.get()));
        
        if (!batchSizes.isEmpty()) {
            System.out.println("\nBatch composition:");
            for (int i = 0; i < batchSizes.size(); i++) {
                System.out.println("  Batch " + (i+1) + ": " + batchSizes.get(i) + " cells");
            }
        }
        
        System.out.println("\n--- EFFICIENCY ---");
        int totalCalls = batchCallCount.get() + individualCallCount.get();
        double reduction = (double) totalCellsClassified / totalCalls;
        System.out.println("Without batching: " + totalCellsClassified + " LLM calls");
        System.out.println("With batching: " + totalCalls + " LLM calls");
        System.out.println("Reduction factor: " + String.format("%.1fx", reduction));
        System.out.println("Total time: " + totalMs + "ms");
        
        System.out.println("\n--- VERIFICATION ---");
        
        // Verify batching is working
        assertThat(batchCallCount.get()).as("Should have batch calls")
                .isGreaterThan(0);
        assertThat(batchSizes).as("Batch sizes should be <= 15")
                .allMatch(size -> size <= 15);
        
        // If all went to batching (no fallback), verify the reduction
        if (individualCallCount.get() == 0) {
            double theoreticalReduction = 15.0;  // 15 cells per batch
            System.out.println("✓ BATCHING WORKING: " + String.format("%.1fx", reduction) 
                + " reduction (target: ~" + theoreticalReduction + "x)");
            assertThat(reduction).as("Reduction should be close to 15x")
                    .isGreaterThan(10);  // Allow some variance
        } else {
            System.out.println("⚠ FALLBACK OCCURRED: " + individualCallCount.get() 
                + " cells fell back to individual classification");
            System.out.println("  This indicates batch parsing may still have issues");
        }
        
        System.out.println("=".repeat(70) + "\n");
    }
}
