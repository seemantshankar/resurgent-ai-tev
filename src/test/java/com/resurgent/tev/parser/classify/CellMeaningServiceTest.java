package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.discover.PacketCell;
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

class CellMeaningServiceTest {

    private static final String AC_PATH = "Project Cost > Plant & Machinery > Air Conditioning";

    @TempDir
    Path tempDir;

    @Test
    void lookupFindsOmArhamStyleSheetWhenTabNameHasTrailingSpace() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("P  L ");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("Insurance");
            row.createCell(9).setCellValue(12.0);
            xlsx = tempDir.resolve("pl-trailing-space.xlsx");
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("pl-trailing-space.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        CellMeaningService meanings = new CellMeaningService();
        CellMeaning fromConcat = meanings.lookup(db, ingest.parseRunId(), "P  L !J1");
        assertThat(fromConcat.cell().coord()).isEqualTo("J1");
        CellMeaning fromQuoted = meanings.lookup(db, ingest.parseRunId(), "'P  L '!J1");
        assertThat(fromQuoted.cell().coord()).isEqualTo("J1");
    }

    @Test
    void lookupReturnsStructureLayerABindingPeersAndFacts() throws Exception {
        Path xlsx = writeRolesWorkbook(tempDir);
        Path db = tempDir.resolve("meaning.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm();
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL,
                Triage.MAIN,
                Relevance.PRIMARY,
                List.of(),
                List.of(),
                "Project Cost",
                List.of(new ProjectFactJudgment(
                        null, "Demo Hotel LLP", "Project Identity > Legal Name")));
        llm.layerBFactory = prompt -> {
            List<LayerBLineJudgment> lines = new java.util.ArrayList<>();
            for (PacketCell cell : prompt.packet().cells()) {
                if (!"number".equals(cell.valueType())
                        || (cell.formulaText() != null && !cell.formulaText().isBlank())) {
                    continue;
                }
                String label = LayerBAmountSupport.resolveRowLabel(prompt.packet(), cell);
                if (label != null && label.toLowerCase().contains("civil")) {
                    lines.add(new LayerBLineJudgment(
                            cell.coord(), "Air Conditioning", AC_PATH, AmountRole.ADD,
                            List.of(), null, List.of()));
                } else if (label != null && label.toLowerCase().contains("ac")) {
                    lines.add(new LayerBLineJudgment(
                            cell.coord(), "Less: AC", AC_PATH, AmountRole.DEDUCT,
                            List.of(), null,
                            List.of(new LinePeerRef("Costs!B2", PeerReason.ANTI_DOUBLE_COUNT))));
                }
            }
            return lines;
        };

        new ClassifyService(llm).classify(db, ingest.parseRunId());

        NomenclatureBinding deductBinding;
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            deductBinding = repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId()).stream()
                    .filter(b -> b.verbatim() != null && b.verbatim().toLowerCase().contains("ac"))
                    .findFirst()
                    .orElseThrow();
        }

        CellMeaning deduct = new CellMeaningService().lookup(
                db, ingest.parseRunId(), "Costs!" + coordForCell(db, deductBinding.cellId()));
        assertThat(deduct.nomenclatureBinding()).isNotNull();
        assertThat(deduct.peers()).hasSize(1);
        assertThat(deduct.peers().get(0).pathResolved()).isTrue();
        assertThat(deduct.candidates()).isNotEmpty();
        assertThat(deduct.dispositions()).isNotEmpty();
        assertThat(deduct.interpretation()).isNotNull();
        assertThat(deduct.interpretation().nomenclatureStatus()).isEqualTo(NomenclatureStatus.BOUND);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectProjectFactBindingsForParseRun(ingest.parseRunId()))
                    .anyMatch(f -> "Project Identity > Legal Name".equals(f.factPath()));
        }
    }

    private static String coordForCell(Path db, long cellId) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            return repo.selectCellPacketViews(List.of(cellId)).get(0).coord();
        }
    }

    private static Path writeRolesWorkbook(Path tempDir) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row add = sheet.createRow(1);
            add.createCell(0).setCellValue("Civil Works");
            add.createCell(1).setCellValue(100.0);
            add.createCell(4).setCellValue("Less: AC");
            add.createCell(5).setCellValue(500.0);
            Path file = tempDir.resolve("cell-meaning-peers.xlsx");
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
            return file;
        }
    }
}
