package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.NomenclatureBinding;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Layer B must not bind a helper bbox when Layer A already triaged it scratch.
 * Region layout may still draw a helper box; triage wins for binding.
 */
class LayerBSkipsSoftTriageHelperTest {

    @TempDir
    Path tempDir;

    @Test
    void bindSkipsHelperIslandWhenLayerATriageIsScratch() throws Exception {
        Path xlsx = tempDir.resolve("sticky.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("PREOP");
            Row title = sheet.createRow(3);
            title.createCell(2).setCellValue("PRELIMINARY & PRE-OPERATIVE EXPENSES");
            Row r9 = sheet.createRow(8);
            r9.createCell(2).setCellValue("Deposits");
            r9.createCell(3).setCellValue(1_100_000);
            Row r10 = sheet.createRow(9);
            r10.createCell(2).setCellValue("Establishment");
            r10.createCell(3).setCellValue(500_000);
            Row total = sheet.createRow(19);
            total.createCell(2).setCellValue("TOTAL");
            total.createCell(3).setCellFormula("SUM(D9:D10)");
            // Sticky-note residual beside the schedule (Excel F19).
            sheet.createRow(18).createCell(5).setCellFormula("D20-D9");
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("sticky.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 21L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        ClassifierLlm llm = new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of(
                        new RegionProposal("main", "C4:D20", "preop_main", "schedule"),
                        new RegionProposal("helper", "F19", "side_note", "sticky arithmetic"));
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                if ("helper".equals(prompt.structuralRole())) {
                    return new LayerAJudgment(
                            ScheduleFamily.NONE,
                            Triage.SCRATCH,
                            Relevance.NOISE,
                            List.of(),
                            List.of(),
                            null,
                            "Unlabelled side computation scratch pad beside the schedule.");
                }
                return new LayerAJudgment(
                        ScheduleFamily.CAPEX_DETAIL,
                        Triage.MAIN,
                        Relevance.PRIMARY,
                        List.of("Deposits", "Establishment", "TOTAL"),
                        List.of(),
                        null,
                        "Preliminary and pre-operative expense schedule.");
            }

            @Override
            public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
                return List.of(
                        new LayerBAssignment(
                                9,
                                null,
                                "economic",
                                "Project Cost > Preliminary & Pre-operative Expenses"),
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

        new ClassifyService(llm).classify(db, ingest.parseRunId());
        BindSummary bound =
                new ClassifyService(llm).bindSheets(db, ingest.parseRunId(), List.of("PREOP"));
        assertThat(bound.boundCells()).isGreaterThan(0);

        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(database.connection());
            List<NomenclatureBinding> bindings =
                    repo.selectNomenclatureBindings(ingest.parseRunId());
            assertThat(bindings).isNotEmpty();

            // F19 must not be bound — helper bbox, Layer A triage scratch.
            boolean f19Bound = database.connection()
                    .prepareStatement(
                            "SELECT 1 FROM nomenclature_binding b"
                                    + " JOIN cell c ON c.cell_id = b.cell_id"
                                    + " WHERE c.coord = 'F19'")
                    .executeQuery()
                    .next();
            assertThat(f19Bound).isFalse();

            // Schedule amounts may still bind.
            boolean d9Bound = database.connection()
                    .prepareStatement(
                            "SELECT 1 FROM nomenclature_binding b"
                                    + " JOIN cell c ON c.cell_id = b.cell_id"
                                    + " WHERE c.coord = 'D9'")
                    .executeQuery()
                    .next();
            assertThat(d9Bound).isTrue();
        }
    }

    @Test
    void bindEligibilityRejectsSoftTriageEvenWhenStructuralRoleIsHelper() {
        CandidateRow helper = new CandidateRow(
                57L,
                1L,
                1L,
                "child",
                1L,
                16,
                6,
                21,
                6,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                "2026-09-28T00:00:00Z",
                "helper");
        PacketDisposition scratch = new PacketDisposition(
                57L,
                1L,
                "scratch_pad",
                Triage.SCRATCH,
                Relevance.NOISE,
                List.of(),
                List.of(),
                null,
                "Side scratch pad.",
                1L,
                false);
        assertThat(ClassifyService.isEligible(helper)).isTrue();
        assertThat(ClassifyService.isBindEligible(helper, scratch)).isFalse();
        assertThat(ClassifyService.isBindEligible(helper, null)).isTrue();
    }
}
