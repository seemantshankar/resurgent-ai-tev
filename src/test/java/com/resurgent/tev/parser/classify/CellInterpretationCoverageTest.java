package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Primary seam: ingest → discover → classify → cell-meaning for #116. */
class CellInterpretationCoverageTest {

    private static final String AC_PATH = "Project Cost > Plant & Machinery > Air Conditioning";

    @TempDir
    Path tempDir;

    @Test
    void classifyWritesOneInterpretationPerPersistedCell() throws Exception {
        Path xlsx = coverageWorkbook();
        Path db = tempDir.resolve("coverage.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        long cellCount;
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            cellCount = new WorkspaceRepository(workspace.connection())
                    .countCellsForParseRun(ingest.parseRunId());
        }
        assertThat(cellCount).isGreaterThan(0);

        ClassifySummary summary = new ClassifyService(bindingLlm("B2")).classify(db, ingest.parseRunId());

        assertThat(summary.interpretationCount()).isEqualTo((int) cellCount);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.countInterpretationsForParseRun(ingest.parseRunId()))
                    .isEqualTo(cellCount);
            List<CellInterpretation> rows =
                    repo.selectCellInterpretationsForParseRun(ingest.parseRunId());
            assertThat(rows).anyMatch(row -> NomenclatureStatus.BOUND.equals(row.nomenclatureStatus())
                    || NomenclatureStatus.UNBOUND.equals(row.nomenclatureStatus()));
            assertThat(rows)
                    .anyMatch(row -> NomenclatureStatus.NOT_APPLICABLE.equals(row.nomenclatureStatus()));
        }
    }

    @Test
    void cellMeaningExposesInterpretationAfterClassifyAndAbsentBefore() throws Exception {
        Path xlsx = rolesWorkbook();
        Path db = tempDir.resolve("meaning.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        CellMeaning before = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B2");
        assertThat(before.interpretation()).isNull();

        new ClassifyService(bindingLlm("B2")).classify(db, ingest.parseRunId());

        CellMeaning amount = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B2");
        assertThat(amount.interpretation()).isNotNull();
        assertThat(amount.interpretation().nomenclatureStatus()).isEqualTo(NomenclatureStatus.BOUND);
        assertThat(amount.interpretation().nomenclaturePath()).isEqualTo(AC_PATH);
        assertThat(amount.interpretation().amountRole()).isEqualTo(AmountRole.ADD);
        assertThat(amount.interpretation().softLeaf()).isTrue();
        assertThat(amount.interpretation().viaAlias()).isFalse();
        assertThat(amount.interpretation().valueOrigin()).isEqualTo("literal");
        assertThat(amount.interpretation().resultingValue()).contains("100");

        CellMeaning label = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!A2");
        assertThat(label.interpretation()).isNotNull();
        assertThat(label.interpretation().nomenclatureStatus())
                .isEqualTo(NomenclatureStatus.NOT_APPLICABLE);
        assertThat(label.interpretation().nomenclaturePath()).isNull();
    }

    @Test
    void unboundNumericAndBoundPathAndProjectFactIndependence() throws Exception {
        Path xlsx = rolesWorkbook();
        Path db = tempDir.resolve("status.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        ClassifyServiceTest.FakeClassifierLlm llm = bindingLlm("B2");
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL,
                Triage.MAIN,
                Relevance.PRIMARY,
                List.of(),
                List.of(),
                "Project Cost",
                List.of(new ProjectFactJudgment(
                        "A2", "Demo Hotel LLP", "Project Identity > Legal Name")));

        new ClassifyService(llm).classify(db, ingest.parseRunId());

        CellMeaning bound = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B2");
        assertThat(bound.interpretation().nomenclatureStatus()).isEqualTo(NomenclatureStatus.BOUND);
        assertThat(bound.interpretation().nomenclaturePath()).isEqualTo(AC_PATH);

        CellMeaning unbound = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!F2");
        assertThat(unbound.nomenclatureBinding()).isNull();
        assertThat(unbound.interpretation().nomenclatureStatus()).isEqualTo(NomenclatureStatus.UNBOUND);

        CellMeaning factCell = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!A2");
        assertThat(factCell.projectFacts())
                .anyMatch(f -> "Project Identity > Legal Name".equals(f.factPath()));
        assertThat(factCell.interpretation().nomenclatureStatus())
                .isEqualTo(NomenclatureStatus.NOT_APPLICABLE);
        assertThat(factCell.nomenclatureBinding()).isNull();
    }

    @Test
    void mergedParticipantsAreNotApplicableAndDoNotDuplicateBoundAmounts() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Merged");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row body = sheet.createRow(1);
            body.createCell(0).setCellValue("Civil");
            body.createCell(1).setCellValue(250.0);
            body.createCell(4).setCellValue("Side");
            body.createCell(5).setCellValue(10.0);
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 1, 1)); // B2:B3
            Row extra = sheet.createRow(2);
            extra.createCell(0).setCellValue("note");
            extra.createCell(4).setCellValue("side2");
            extra.createCell(5).setCellValue(11.0);
            xlsx = writeWorkbook(workbook, "merged.xlsx");
        }
        Path db = tempDir.resolve("merged.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        ClassifySummary summary = new ClassifyService(bindingLlm("B2")).classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(summary.interpretationCount())
                    .isEqualTo((int) repo.countCellsForParseRun(ingest.parseRunId()));
            long bound = repo.selectCellInterpretationsForParseRun(ingest.parseRunId()).stream()
                    .filter(row -> NomenclatureStatus.BOUND.equals(row.nomenclatureStatus()))
                    .count();
            assertThat(bound).isEqualTo(1);
            assertThat(repo.selectCellInterpretationsForParseRun(ingest.parseRunId()))
                    .anyMatch(row -> NomenclatureStatus.NOT_APPLICABLE.equals(row.nomenclatureStatus()));
        }
    }

    @Test
    void reclassifyReplacesInterpretationsAtomically() throws Exception {
        Path xlsx = rolesWorkbook();
        Path db = tempDir.resolve("reclassify.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        new ClassifyService(bindingLlm("B2")).classify(db, ingest.parseRunId());

        CellMeaning first = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B2");
        assertThat(first.interpretation().nomenclaturePath()).isEqualTo(AC_PATH);

        ClassifyServiceTest.FakeClassifierLlm llm = bindingLlm("B2");
        llm.layerBFactory = prompt -> prompt.packet().cells().stream()
                .filter(cell -> "B2".equalsIgnoreCase(cell.coord()))
                .findFirst()
                .map(cell -> List.of(new LayerBLineJudgment(
                        cell.coord(),
                        "Structure",
                        "Project Cost > Civil Works > Structure",
                        AmountRole.ADD,
                        List.of(),
                        0.9)))
                .orElse(List.of());
        new ClassifyService(llm).classify(db, ingest.parseRunId());

        CellMeaning second = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B2");
        assertThat(second.interpretation().nomenclaturePath())
                .isEqualTo("Project Cost > Civil Works > Structure");
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.countInterpretationsForParseRun(ingest.parseRunId()))
                    .isEqualTo(repo.countCellsForParseRun(ingest.parseRunId()));
        }
    }

    @Test
    void classifyWriteFailureRollsBackToPriorInterpretationSnapshot() throws Exception {
        Path xlsx = rolesWorkbook();
        Path db = tempDir.resolve("rollback.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        new ClassifyService(bindingLlm("B2")).classify(db, ingest.parseRunId());

        String priorPath;
        long priorCount;
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            priorCount = repo.countInterpretationsForParseRun(ingest.parseRunId());
            priorPath = repo.selectCellInterpretation(
                            ingest.parseRunId(),
                            cellId(db, "Costs", "B2"))
                    .orElseThrow()
                    .nomenclaturePath();
        }

        InterpretationWriter failing = new InterpretationWriter() {
            @Override
            public int write(
                    WorkspaceRepository repo,
                    long parseRunId,
                    List<NomenclatureBinding> bindings,
                    java.util.Map<Long, UnboundReason> unboundReasons) throws SQLException {
                throw new SQLException("forced interpretation write failure");
            }
        };
        ClassifyServiceTest.FakeClassifierLlm llm = bindingLlm("B2");
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.ASSUMPTIONS, Triage.SCRATCH, Relevance.NOISE,
                List.of(), List.of(), null);
        assertThatThrownBy(() -> new ClassifyService(llm, new DiscoverService(), failing)
                        .classify(db, ingest.parseRunId()))
                .isInstanceOf(ClassifyException.class)
                .hasMessageContaining("forced interpretation write failure");

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.countInterpretationsForParseRun(ingest.parseRunId())).isEqualTo(priorCount);
            assertThat(repo.selectCellInterpretation(ingest.parseRunId(), cellId(db, "Costs", "B2"))
                            .orElseThrow()
                            .nomenclaturePath())
                    .isEqualTo(priorPath);
            assertThat(repo.selectPacketDispositionsForParseRun(ingest.parseRunId()))
                    .noneMatch(row -> ScheduleFamily.ASSUMPTIONS.equals(row.scheduleFamily()));
        }
    }

    @Test
    void successfulRediscoverClearsInterpretations() throws Exception {
        Path xlsx = rolesWorkbook();
        Path db = tempDir.resolve("rediscover.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        new ClassifyService(bindingLlm("B2")).classify(db, ingest.parseRunId());
        assertThat(new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B2").interpretation())
                .isNotNull();

        new DiscoverService().discover(db, ingest.parseRunId());

        assertThat(new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B2").interpretation())
                .isNull();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            assertThat(new WorkspaceRepository(workspace.connection())
                            .countInterpretationsForParseRun(ingest.parseRunId()))
                    .isZero();
        }
    }

    @Test
    void parseRunsStayIsolated() throws Exception {
        Path firstXlsx = rolesWorkbook();
        Path secondXlsx = costWorkbook("Costs2");
        Path db = tempDir.resolve("isolated.db");
        IngestSummary first = new IngestService().ingest(firstXlsx, 1L, db);
        new DiscoverService().discover(db, first.parseRunId());
        new ClassifyService(bindingLlm("B2")).classify(db, first.parseRunId());

        IngestSummary second = new IngestService().ingest(secondXlsx, 1L, db);
        new DiscoverService().discover(db, second.parseRunId());
        new ClassifyService(bindingLlm("B2")).classify(db, second.parseRunId());

        assertThat(first.parseRunId()).isNotEqualTo(second.parseRunId());
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.countInterpretationsForParseRun(first.parseRunId()))
                    .isEqualTo(repo.countCellsForParseRun(first.parseRunId()));
            assertThat(repo.countInterpretationsForParseRun(second.parseRunId()))
                    .isEqualTo(repo.countCellsForParseRun(second.parseRunId()));
        }
    }

    private static ClassifyServiceTest.FakeClassifierLlm bindingLlm(String coord) {
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm();
        llm.layerBFactory = prompt -> prompt.packet().cells().stream()
                .filter(cell -> coord.equalsIgnoreCase(cell.coord()))
                .findFirst()
                .map(cell -> List.of(new LayerBLineJudgment(
                        cell.coord(),
                        "Air Conditioning",
                        AC_PATH,
                        AmountRole.ADD,
                        List.of(),
                        null,
                        List.of())))
                .orElse(List.of());
        return llm;
    }

    private Path coverageWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row body = sheet.createRow(1);
            body.createCell(0).setCellValue("Civil Works");
            body.createCell(1).setCellValue(100.0);
            body.createCell(4).setCellValue("Glass");
            body.createCell(5).setCellValue(20.0);
            Row hidden = sheet.createRow(2);
            hidden.createCell(0).setCellValue("Hidden");
            hidden.createCell(1).setCellValue(5.0);
            hidden.createCell(4).setCellValue("H2");
            hidden.createCell(5).setCellValue(6.0);
            sheet.getRow(2).setZeroHeight(true);
            XSSFCellStyle styled = workbook.createCellStyle();
            styled.setFillForegroundColor(
                    org.apache.poi.ss.usermodel.IndexedColors.YELLOW.getIndex());
            styled.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            Row blank = sheet.createRow(3);
            blank.createCell(0).setCellStyle(styled);
            return writeWorkbook(workbook, "coverage.xlsx");
        }
    }

    private Path costWorkbook(String sheetName) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row body = sheet.createRow(1);
            body.createCell(0).setCellValue("Civil Works");
            body.createCell(1).setCellValue(100.0);
            body.createCell(4).setCellValue("Glass");
            body.createCell(5).setCellValue(20.0);
            return writeWorkbook(workbook, sheetName.toLowerCase() + ".xlsx");
        }
    }

    private Path rolesWorkbook() throws Exception {
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
            return writeWorkbook(workbook, "roles.xlsx");
        }
    }

    private Path writeWorkbook(XSSFWorkbook workbook, String name) throws Exception {
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        workbook.close();
        return file;
    }

    private static long cellId(Path db, String sheet, String coord) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                var ps = workspace.connection().prepareStatement(
                        "SELECT c.cell_id FROM cell c"
                                + " JOIN worksheet w ON w.worksheet_id = c.worksheet_id"
                                + " WHERE w.sheet_name = ? AND UPPER(c.coord) = ?"
                                + " ORDER BY w.parse_run_id LIMIT 1")) {
            ps.setString(1, sheet);
            ps.setString(2, coord.toUpperCase());
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }
}
