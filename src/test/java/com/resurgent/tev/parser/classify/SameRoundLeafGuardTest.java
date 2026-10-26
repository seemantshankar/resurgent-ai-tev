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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #158: regions asked together cannot see each other's mints, so a sibling
 * that only restates one already staged in that round binds to it.
 */
class SameRoundLeafGuardTest {

    private static final String FREIGHT = "Project Cost > Plant & Machinery > Freight & Forwarding";
    private static final String FREIGHT_AGAIN = "Project Cost > Plant & Machinery > Forwarding and Freight";
    private static final String LINE = "Project Cost > Plant & Machinery > Transmission Line";
    private static final String INVERTERS = "Project Cost > Plant & Machinery > Inverters / PCU";

    @TempDir
    Path tempDir;

    @Test
    void sameRoundSynonymsBindToOneLeafAndAShortNameFollowsTheSpine() throws Exception {
        Path xlsx = tempDir.resolve("rounds.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (String name : new String[] {"ALPHA", "BETA", "GAMMA", "DELTA"}) {
                Sheet sheet = workbook.createSheet(name);
                Row row = sheet.createRow(0);
                row.createCell(0).setCellValue(name + " line");
                row.createCell(1).setCellValue(1000);
            }
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("rounds.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 158L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        ClassifierLlm llm = new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of(new RegionProposal("main", "A1:B1", "schedule", "unit-test region"));
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                return new LayerAJudgment(
                        ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                        List.of("line"), List.of(), null, "Equipment schedule.");
            }

            @Override
            public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
                String path = switch (prompt.sheetName()) {
                    case "ALPHA" -> FREIGHT;
                    case "BETA" -> FREIGHT_AGAIN;
                    case "GAMMA" -> "Project Cost > Plant & Machinery > Inverter";
                    default -> LINE;
                };
                return List.of(new LayerBAssignment(1, null, "economic", path));
            }
        };

        ClassifyService service = new ClassifyService(llm).withTuning(new ClassifyTuning(50, 25, 4));
        service.classify(db, ingest.parseRunId());
        service.bindSheets(db, ingest.parseRunId(), List.of("ALPHA", "BETA", "GAMMA", "DELTA"));

        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(database.connection());
            List<NomenclatureBinding> bindings = repo.selectNomenclatureBindings(ingest.parseRunId());
            assertThat(bindings).extracting(NomenclatureBinding::path)
                    .contains(FREIGHT, INVERTERS, LINE)
                    .doesNotContain(FREIGHT_AGAIN, "Project Cost > Plant & Machinery > Inverter");
            assertThat(bindings).filteredOn(b -> FREIGHT.equals(b.path())).hasSizeGreaterThanOrEqualTo(2);

            NomenclatureCatalog catalog = new NomenclatureCatalog(repo);
            assertThat(catalog.sliceForMandate(158L).node(FREIGHT)).isPresent();
            assertThat(catalog.sliceForMandate(158L).node(FREIGHT_AGAIN)).isEmpty();
            assertThat(catalog.sliceForMandate(158L).node(
                    "Project Cost > Plant & Machinery > Inverter")).isEmpty();
            assertThat(catalog.sliceForMandate(158L).node(LINE)).isPresent();
        }
    }
}
