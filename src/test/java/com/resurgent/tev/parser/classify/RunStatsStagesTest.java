package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThatCode;

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

/** A real classify run, header-geometry calls included, must leave stats the database accepts. */
class RunStatsStagesTest {

    @TempDir
    Path tempDir;

    @Test
    void statsOfAClassifyRunThatAskedForHeaderGeometryCanBeSaved() throws Exception {
        Path xlsx = tempDir.resolve("one.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("ONE");
            for (int r = 0; r < 4; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("Item " + r);
                row.createCell(1).setCellValue(1000.0 * (r + 1));
            }
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("one.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 51L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        LlmStats.GLOBAL.reset();
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
            public String classifyCellJson(String system, String user, int maxTokens) {
                LlmStats.GLOBAL.recordCall("fake/model", 10, 1, 0.0, 5); // the stage a call lands in is what is under test
                return "{\"bands\":[],\"rowLabelColumns\":[\"A\"]}";
            }
        };

        new ClassifyService(llm).classify(db, ingest.parseRunId());

        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(database.connection());
            assertThatCode(() -> LlmStatsPersistence.write(repo, ingest.parseRunId(), LlmStats.GLOBAL))
                    .doesNotThrowAnyException();
        }
    }
}
