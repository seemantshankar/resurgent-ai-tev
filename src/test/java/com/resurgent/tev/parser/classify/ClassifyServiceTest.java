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

            // The normal classify run saves each cell's row/column labels (not only classify --sheet).
            try (var st = db.connection().createStatement();
                    var rs = st.executeQuery(
                            "SELECT COUNT(*) FROM cell_interpretation_evidence e JOIN cell c USING (cell_id)"
                                    + " WHERE e.role = 'row_header' AND e.resolution = 'resolved'"
                                    + " AND c.coord = 'B5' AND e.source_text = 'Item 4'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("B5 keeps its row label 'Item 4'").isEqualTo(1);
            }
            try (var st = db.connection().createStatement();
                    var rs = st.executeQuery("SELECT COUNT(*) FROM cell_interpretation")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isPositive();
            }
        }
    }
}
