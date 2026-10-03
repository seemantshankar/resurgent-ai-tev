package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run scoped to one sheet must not spend model calls binding the rest of the workbook. */
class ScopedBindingTest {

    @TempDir
    Path tempDir;

    @Test
    void aSheetScopedClassifyBindsOnlyTheSheetsInScope() throws Exception {
        Path xlsx = tempDir.resolve("three.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (String name : new String[] {"ONE", "TWO", "THREE"}) {
                Sheet sheet = workbook.createSheet(name);
                for (int r = 0; r < 4; r++) {
                    Row row = sheet.createRow(r);
                    row.createCell(0).setCellValue("Item " + name + r);
                    row.createCell(1).setCellValue(1000.0 * (r + 1));
                }
            }
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("three.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 41L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        // Discovery leaves structural child regions on every sheet; give TWO one, as a real workbook has.
        try (com.resurgent.tev.parser.db.WorkspaceDatabase database = com.resurgent.tev.parser.db.WorkspaceDatabase.open(db)) {
            com.resurgent.tev.parser.db.WorkspaceRepository repo =
                    new com.resurgent.tev.parser.db.WorkspaceRepository(database.connection());
            var two = repo.selectWorksheetsForParseRun(ingest.parseRunId()).stream()
                    .filter(w -> w.sheetName().equals("TWO")).findFirst().orElseThrow();
            var parent = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> c.worksheetId() == two.worksheetId() && "coverage_parent".equals(c.candidateKind()))
                    .findFirst().orElseThrow();
            List<Long> members = repo.selectCellIdsInBbox(two.worksheetId(), 1, 1, 4, 2);
            repo.insertCandidate(new com.resurgent.tev.parser.db.CandidateWrite(
                    ingest.parseRunId(), two.worksheetId(), "child", parent.candidateId(), 1, 1, 4, 2,
                    null, null, null, false, 0.9, "test", "structural child on an out-of-scope sheet", "main"), members);
        }

        Set<String> sheetsBound = ConcurrentHashMap.newKeySet();
        ClassifierLlm llm = new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of(new RegionProposal("main", "A1:B4", "schedule", "unit-test region"));
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                return new LayerAJudgment(
                        ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                        List.of(), List.of(), null, "Expense schedule.");
            }

            @Override
            public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
                sheetsBound.add(prompt.sheetName());
                return List.of();
            }
        };

        new ClassifyService(llm).withSheetScope(List.of("ONE")).classify(db, ingest.parseRunId());

        assertThat(sheetsBound).as("sheets the model was asked to bind").containsOnly("ONE");
    }
}
