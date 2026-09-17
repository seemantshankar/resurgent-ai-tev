package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam: bindings the cell graph proves on its own, with no LLM answer behind them.
 * Role comes from the formula, so a formula cell can finally take {@code add} and a
 * leaf rollup can mean something.
 */
class DeterministicBindingTest {

    @TempDir
    Path tempDir;

    private static final String AIR_CONDITIONING =
            "Project Cost > Plant & Machinery > Air Conditioning";
    private static final String ELEVATOR = "Project Cost > Plant & Machinery > Elevator / Lift";

    private Path projectCostWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount (Rs)");
            Row airConditioning = sheet.createRow(1);
            airConditioning.createCell(0).setCellValue("Air Conditioning");
            airConditioning.createCell(1).setCellValue(100.0);
            Row elevator = sheet.createRow(2);
            elevator.createCell(0).setCellValue("Elevator / Lift");
            elevator.createCell(1).setCellFormula("B2*2");
            Row total = sheet.createRow(3);
            total.createCell(0).setCellValue("Project Cost");
            total.createCell(1).setCellFormula("SUM(B2:B3)");
            Row rooms = sheet.createRow(4);
            rooms.createCell(0).setCellValue("No. of Rooms");
            rooms.createCell(1).setCellValue(40.0);
            Row rate = sheet.createRow(5);
            rate.createCell(0).setCellValue("Room Rate");
            rate.createCell(1).setCellValue(5000.0);
            Row guests = sheet.createRow(6);
            guests.createCell(0).setCellValue("No. of Guests");
            guests.createCell(1).setCellValue(380.0);
            Row guestTotal = sheet.createRow(7);
            guestTotal.createCell(0).setCellValue("Total Guests");
            guestTotal.createCell(1).setCellFormula("SUM(B5:B7)");

            // Evaluate so formula cells carry a cached value, as a client FM does.
            org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator.evaluateAllFormulaCells(workbook);

            Path file = tempDir.resolve("project-cost.xlsx");
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
            return file;
        }
    }

    private record Bound(Path db, long parseRunId) {}

    private Bound classify() throws Exception {
        Path xlsx = projectCostWorkbook();
        Path db = tempDir.resolve("deterministic.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            new NomenclatureCatalog(new WorkspaceRepository(workspace.connection()))
                    .confirmIndustry(1L, "hotel");
        }
        new ClassifyService(new ClassifyServiceTest.FakeClassifierLlm())
                .classify(db, ingest.parseRunId());
        return new Bound(db, ingest.parseRunId());
    }

    private static Map<String, NomenclatureBinding> byCoord(
            WorkspaceRepository repo, long parseRunId) throws Exception {
        Map<Long, String> coords = repo.selectInterpretationCellsForParseRun(parseRunId).stream()
                .collect(Collectors.toMap(
                        com.resurgent.tev.parser.db.InterpretationCellView::cellId,
                        com.resurgent.tev.parser.db.InterpretationCellView::coord));
        return repo.selectNomenclatureBindingsForParseRun(parseRunId).stream()
                .collect(Collectors.toMap(
                        binding -> coords.get(binding.cellId()), Function.identity()));
    }

    @Test
    void aLiteralUnderAMoneyTotalBindsAsAddFromItsOwnLabel() throws Exception {
        Bound bound = classify();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(bound.db())) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            NomenclatureBinding binding = byCoord(repo, bound.parseRunId()).get("B2");

            assertThat(binding).isNotNull();
            assertThat(binding.path()).isEqualTo(AIR_CONDITIONING);
            assertThat(binding.amountRole()).isEqualTo(AmountRole.ADD);
            assertThat(binding.source()).isEqualTo(BindingSource.INPUT);
            assertThat(binding.softLeaf()).isFalse();
        }
    }

    @Test
    void aFormulaCellCanTakeAddSoALeafRollupMeansSomething() throws Exception {
        Bound bound = classify();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(bound.db())) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            NomenclatureBinding binding = byCoord(repo, bound.parseRunId()).get("B3");

            assertThat(binding).isNotNull();
            assertThat(binding.path()).isEqualTo(ELEVATOR);
            assertThat(binding.amountRole())
                    .as("the old role gate forced every formula to helper or total")
                    .isEqualTo(AmountRole.ADD);
            assertThat(binding.source()).isEqualTo(BindingSource.DERIVED);
            assertThat(repo.sumAddAmountsForPath(bound.parseRunId(), ELEVATOR)).isEqualTo(200.0);
        }
    }

    @Test
    void aTotalWhoseNameIsNotACatalogLeafStaysUnboundRatherThanGuessing() throws Exception {
        Bound bound = classify();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(bound.db())) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            long totalCellId = repo.selectInterpretationCellsForParseRun(bound.parseRunId())
                    .stream()
                    .filter(cell -> "B4".equals(cell.coord()))
                    .findFirst()
                    .orElseThrow()
                    .cellId();

            assertThat(byCoord(repo, bound.parseRunId()).get("B4"))
                    .as("'Project Cost' is a mid-level, not a leaf join key")
                    .isNull();
            assertThat(repo.selectCellInterpretation(bound.parseRunId(), totalCellId)
                            .orElseThrow()
                            .unboundReason())
                    .isEqualTo(UnboundReason.LLM_UNAVAILABLE.wireName());
            assertThat(repo.selectAggregationsForParseRun(bound.parseRunId()))
                    .anySatisfy(aggregation -> {
                        assertThat(aggregation.headCellId()).isEqualTo(totalCellId);
                        assertThat(aggregation.resolvedKind()).isEqualTo(CellKind.MONEY);
                    });
        }
    }

    @Test
    void aDriverAndAMemberOfANonMoneyGroupNeverTakeACostRole() throws Exception {
        Bound bound = classify();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(bound.db())) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            Map<String, NomenclatureBinding> bindings = byCoord(repo, bound.parseRunId());

            for (String coord : List.of("B5", "B6", "B7", "B8")) {
                NomenclatureBinding binding = bindings.get(coord);
                if (binding == null) {
                    continue;
                }
                assertThat(binding.amountRole())
                        .as(coord + " is a count or a driver, never a cost")
                        .isEqualTo(AmountRole.HELPER);
            }
        }
    }

    @Test
    void everyUnboundNumericCellCarriesAReason() throws Exception {
        Bound bound = classify();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(bound.db())) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CellInterpretation> interpretations =
                    repo.selectCellInterpretationsForParseRun(bound.parseRunId());
            List<CellInterpretation> unboundNumerics = interpretations.stream()
                    .filter(row -> NomenclatureStatus.UNBOUND.equals(row.nomenclatureStatus()))
                    .toList();

            assertThat(unboundNumerics).isNotEmpty();
            assertThat(unboundNumerics)
                    .allMatch(row -> row.unboundReason() != null && !row.unboundReason().isBlank());
            assertThat(unboundNumerics)
                    .allMatch(row -> UnboundReason.wireNames().contains(row.unboundReason()));
        }
    }

    @Test
    void noRowGetsMoreThanOnePathAcrossItsCells() throws Exception {
        Bound bound = classify();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(bound.db())) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            Map<Long, Integer> rowByCell =
                    repo.selectInterpretationCellsForParseRun(bound.parseRunId()).stream()
                            .collect(Collectors.toMap(
                                    com.resurgent.tev.parser.db.InterpretationCellView::cellId,
                                    com.resurgent.tev.parser.db.InterpretationCellView::rowNum));
            Map<Integer, List<String>> pathsByRow =
                    repo.selectNomenclatureBindingsForParseRun(bound.parseRunId()).stream()
                            .collect(Collectors.groupingBy(
                                    binding -> rowByCell.get(binding.cellId()),
                                    Collectors.mapping(
                                            NomenclatureBinding::path, Collectors.toList())));

            assertThat(pathsByRow.values())
                    .allSatisfy(paths -> assertThat(paths.stream().distinct().toList()).hasSize(1));
        }
    }
}
