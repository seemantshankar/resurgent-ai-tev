package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A cell can sit in two regions that are both kept (a supporting band drawn inside a main
 * schedule). Layer A used to file such supporting regions as scratch, so this never happened;
 * once they are kept, a cross-sheet link cell inside the overlap must still get one binding.
 */
class OverlappingRegionsBindOnceTest {

    @TempDir
    Path tempDir;

    @Test
    void aCrossSheetLinkInsideTwoKeptRegionsIsBoundOnce() throws Exception {
        Path xlsx = tempDir.resolve("overlap.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet src = workbook.createSheet("SRC");
            src.createRow(1).createCell(2).setCellValue("SOURCES OF FUNDS");
            src.createRow(2).createCell(2).setCellValue("Equity");
            src.getRow(2).createCell(3).setCellValue(500);
            src.createRow(3).createCell(2).setCellValue("Debt");
            src.getRow(3).createCell(3).setCellValue(300);
            src.createRow(4).createCell(2).setCellValue("TOTAL");
            src.getRow(4).createCell(3).setCellFormula("SUM(D3:D4)");

            Sheet preop = workbook.createSheet("PREOP");
            preop.createRow(3).createCell(2).setCellValue("PRELIMINARY EXPENSES");
            preop.createRow(8).createCell(2).setCellValue("Deposits");
            preop.getRow(8).createCell(3).setCellValue(1_100_000);
            preop.createRow(9).createCell(2).setCellValue("Establishment");
            preop.getRow(9).createCell(3).setCellValue(500_000);
            preop.createRow(10).createCell(2).setCellValue("Equity brought in");
            preop.getRow(10).createCell(3).setCellFormula("SRC!D3");
            preop.createRow(19).createCell(2).setCellValue("TOTAL");
            preop.getRow(19).createCell(3).setCellFormula("SUM(D9:D10)");
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("overlap.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 22L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        ClassifierLlm llm = new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                if ("SRC".equals(prompt.sheetName())) {
                    return List.of(new RegionProposal("main", "C2:D5", "sources", "schedule"));
                }
                return List.of(
                        new RegionProposal("main", "C4:D20", "preop_main", "schedule"),
                        new RegionProposal("helper", "D10:D12", "preop_band", "supporting band"));
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                return new LayerAJudgment(
                        ScheduleFamily.CAPEX_DETAIL,
                        Triage.MAIN,
                        "helper".equals(prompt.structuralRole()) ? Relevance.SUPPORTING : Relevance.PRIMARY,
                        List.of(),
                        List.of(),
                        null,
                        "Schedule.");
            }

            @Override
            public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
                String path = "Project Cost > Preliminary & Pre-operative Expenses";
                if ("SRC".equals(prompt.sheetName())) {
                    return List.of(
                            new LayerBAssignment(3, null, "economic", path),
                            new LayerBAssignment(4, null, "economic", path),
                            new LayerBAssignment(5, null, "economic", path));
                }
                // Row 11 (the cross-sheet link) is left for the graph, as in a real run.
                return List.of(
                        new LayerBAssignment(9, null, "economic", path),
                        new LayerBAssignment(10, null, "economic", path),
                        new LayerBAssignment(20, null, "economic", path));
            }
        };

        new ClassifyService(llm).classify(db, ingest.parseRunId());
        new ClassifyService(llm).bindSheets(db, ingest.parseRunId(), List.of("SRC", "PREOP"));

        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            var rs = database.connection()
                    .prepareStatement(
                            "SELECT c.coord, COUNT(*) FROM nomenclature_binding b"
                                    + " JOIN cell c ON c.cell_id = b.cell_id"
                                    + " GROUP BY b.cell_id HAVING COUNT(*) > 1")
                    .executeQuery();
            assertThat(rs.next()).as("a cell bound twice").isFalse();

            var link = database.connection()
                    .prepareStatement(
                            "SELECT COUNT(*) FROM nomenclature_binding b"
                                    + " JOIN cell c ON c.cell_id = b.cell_id WHERE c.coord = 'D11'")
                    .executeQuery();
            link.next();
            assertThat(link.getInt(1)).isEqualTo(1);
        }
    }
}
