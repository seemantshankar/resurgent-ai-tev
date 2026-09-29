package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.NomenclatureBinding;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Living ontology + Layer B about-driven path choice. */
class LivingOntologyBindTest {

    @TempDir
    Path tempDir;

    @Test
    void promptCarriesAboutAndPrefersExistingLeaves() {
        LayerBPrompt prompt = new LayerBPrompt(
                "PREOPERATIVE EXP",
                "capex_detail",
                "Appendix 2 preliminary expenses build-up for capital cost.",
                "bind\tC9\t9\tDeposits\n",
                LayerBBinder.allowedPaths(),
                false);
        String user = LayerBPromptAssembler.userMessage(prompt);
        assertThat(user).contains("about: Appendix 2 preliminary expenses build-up for capital cost.");
        assertThat(user).contains("Choose an existing catalog path");
        assertThat(LayerBPromptAssembler.SYSTEM).contains("Prefer an existing catalog leaf");
        assertThat(LayerBPromptAssembler.SYSTEM).contains("Read the about paragraph");
    }

    @Test
    void bindReusesSoftLeafFromLivingOntologyAndLeavesResidualsUnbound() throws Exception {
        Path xlsx = tempDir.resolve("preop.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("PREOP");
            Row title = sheet.createRow(4);
            title.createCell(2).setCellValue("PRELIMINARY & PRE-OPERATIVE EXPENSES");
            Row scale = sheet.createRow(5);
            scale.createCell(3).setCellValue("(Amt. in Rs.)");
            Row r9 = sheet.createRow(8);
            r9.createCell(2).setCellValue("Establishment Cost");
            r9.createCell(3).setCellValue(500_000);
            Row r10 = sheet.createRow(9);
            r10.createCell(2).setCellValue("Sundry");
            r10.createCell(3).setCellValue(50_000);
            Row total = sheet.createRow(19);
            total.createCell(2).setCellValue("TOTAL");
            total.createCell(3).setCellFormula("SUM(D9:D10)");
            Row residual = sheet.createRow(18);
            residual.createCell(5).setCellFormula("D20-D9");
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("preop.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 11L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        AtomicReference<String> aboutSeen = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ClassifierLlm llm = new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of(new RegionProposal(
                        "main", "A1:F25", "preop_main", "unit-test preop region"));
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                return new LayerAJudgment(
                        ScheduleFamily.CAPEX_DETAIL,
                        Triage.MAIN,
                        Relevance.PRIMARY,
                        List.of("Establishment Cost", "Sundry", "TOTAL"),
                        List.of("(Amt. in Rs.)"),
                        null,
                        "This region is the preliminary and pre-operative expense schedule.");
            }

            @Override
            public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
                calls.incrementAndGet();
                aboutSeen.set(prompt.about());
                assertThat(prompt.allowedPaths())
                        .anyMatch(p -> p.contains("Establishment Expenses"));
                return List.of(
                        new LayerBAssignment(
                                9,
                                null,
                                "economic",
                                "Project Cost > Preliminary & Pre-operative Expenses"
                                        + " > Establishment Expenses"),
                        new LayerBAssignment(
                                10,
                                null,
                                "economic",
                                "Project Cost > Preliminary & Pre-operative Expenses"),
                        new LayerBAssignment(
                                20,
                                null,
                                "economic",
                                "Project Cost > Preliminary & Pre-operative Expenses"));
            }
        };

        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(database.connection());
            new NomenclatureCatalog(repo).putSoftLeaf(
                    11L,
                    "Project Cost > Preliminary & Pre-operative Expenses",
                    "Establishment Expenses",
                    List.of("Establishment Expenses", "Establishment Cost"));
        }

        new ClassifyService(llm).classify(db, ingest.parseRunId());
        BindSummary bound =
                new ClassifyService(llm).bindSheets(db, ingest.parseRunId(), List.of("PREOP"));
        assertThat(bound.boundCells()).isGreaterThan(0);
        assertThat(aboutSeen.get()).contains("pre-operative");
        assertThat(calls.get()).isGreaterThan(0);

        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(database.connection());
            List<NomenclatureBinding> bindings = repo.selectNomenclatureBindings(ingest.parseRunId());
            assertThat(bindings)
                    .anySatisfy(b -> assertThat(b.path())
                            .contains("Establishment Expenses"));
            assertThat(bindings)
                    .noneSatisfy(b -> assertThat(b.coord()).isEqualTo("F19"));
            assertThat(bindings)
                    .filteredOn(b -> "add".equals(b.amountRole()))
                    .allSatisfy(b -> assertThat(b.path())
                            .startsWith("Project Cost > Preliminary & Pre-operative Expenses"));
        }
    }

    @Test
    void bindSucceedsWhenModelLeavesCellsUnbound() throws Exception {
        Path xlsx = tempDir.resolve("sparse.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("SPARSE");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("Odd Label Nobody Knows");
            row.createCell(1).setCellValue(42);
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("sparse.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 12L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        ClassifierLlm llm = new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of(new RegionProposal(
                        "main", "A1:B2", "sparse_main", "unit-test sparse"));
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                return new LayerAJudgment(
                        ScheduleFamily.ASSUMPTIONS,
                        Triage.MAIN,
                        Relevance.SUPPORTING,
                        List.of(),
                        List.of(),
                        null,
                        "A small scratch-like assumptions corner.");
            }

            @Override
            public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
                return List.of();
            }
        };
        new ClassifyService(llm).classify(db, ingest.parseRunId());
        BindSummary summary =
                new ClassifyService(llm).bindSheets(db, ingest.parseRunId(), List.of("SPARSE"));
        assertThat(summary.parseRunId()).isEqualTo(ingest.parseRunId());
    }
}
