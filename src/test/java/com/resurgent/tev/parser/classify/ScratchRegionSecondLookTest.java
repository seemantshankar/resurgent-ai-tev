package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Region layout's scratch call used to be final. A scratch region with numbers now goes to Layer A
 * unless an independent opinion confirms it, and Layer A's answer then decides whether it is bound.
 */
class ScratchRegionSecondLookTest {

    @TempDir
    Path tempDir;

    private final List<String> rolesLayerASaw = new ArrayList<>();

    private Path workbook() throws Exception {
        Path xlsx = tempDir.resolve("second-look.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("PREOP");
            sheet.createRow(3).createCell(2).setCellValue("PRELIMINARY EXPENSES");
            sheet.createRow(8).createCell(2).setCellValue("Deposits");
            sheet.getRow(8).createCell(3).setCellValue(1_100_000);
            sheet.createRow(9).createCell(2).setCellValue("Establishment");
            sheet.getRow(9).createCell(3).setCellValue(500_000);
            // A block region layout will call scratch: each line in lakhs, to the right of the schedule.
            sheet.getRow(8).createCell(6).setCellValue(11.0);
            sheet.getRow(9).createCell(6).setCellValue(5.0);
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        return xlsx;
    }

    private ClassifierLlm llm() {
        return new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of(
                        new RegionProposal("main", "C4:D10", "preop_main", "schedule"),
                        new RegionProposal("scratch", "G9:G10", "side_calc", "looks like a side calculation"));
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                rolesLayerASaw.add(prompt.structuralRole());
                return new LayerAJudgment(ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                        List.of(), List.of(), null, "Schedule.");
            }

            @Override
            public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
                String path = "Project Cost > Preliminary & Pre-operative Expenses";
                return List.of(
                        new LayerBAssignment(9, null, "economic", path),
                        new LayerBAssignment(10, null, "economic", path));
            }
        };
    }

    private static RegionTriageClient opinion(String choice, double confidence) {
        return new RegionTriageClient() {
            @Override
            public String model() {
                return "test/d1";
            }

            @Override
            public Opinion decide(String stateJson) {
                return new Opinion(choice, confidence, Map.of("main", 0.1, "scratch", 0.8, "orphan", 0.1));
            }
        };
    }

    private boolean bound(Path db, String coord) throws Exception {
        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            return database.connection()
                    .prepareStatement("SELECT 1 FROM nomenclature_binding b JOIN cell c ON c.cell_id = b.cell_id"
                            + " WHERE c.coord = '" + coord + "'")
                    .executeQuery()
                    .next();
        }
    }

    private Path classify(String name, RegionTriageClient triage) throws Exception {
        Path db = tempDir.resolve(name + ".db");
        IngestSummary ingest = new IngestService().ingest(workbook(), 31L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(llm()).withRegionTriage(triage).classify(db, ingest.parseRunId());
        new ClassifyService(llm()).bindSheets(db, ingest.parseRunId(), List.of("PREOP"));
        return db;
    }

    @Test
    void aScratchRegionTheOpinionDoesNotConfirmIsJudgedByLayerAAndBound() throws Exception {
        Path db = classify("unconfirmed", opinion("main", 0.9));
        assertThat(bound(db, "G9")).isTrue();
        // Layer A is not told region layout's scratch label, which would anchor its answer.
        assertThat(rolesLayerASaw).containsExactlyInAnyOrder("main", "helper");
        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            var rs = database.connection()
                    .prepareStatement("SELECT escalated, choice FROM region_triage_opinion WHERE escalated = 1")
                    .executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("choice")).isEqualTo("main");
        }
    }

    @Test
    void aScratchRegionTheOpinionConfirmsStaysScratchAndIsNotBound() throws Exception {
        Path db = classify("confirmed", opinion("scratch", 0.95));
        assertThat(bound(db, "G9")).isFalse();
        assertThat(bound(db, "D9")).isTrue();
        assertThat(rolesLayerASaw).containsExactly("main");
    }

    @Test
    void withoutADecisionModelAScratchRegionWithNumbersStillGetsLayerAsLook() throws Exception {
        Path db = classify("no-model", null);
        assertThat(bound(db, "G9")).isTrue();
    }
}
