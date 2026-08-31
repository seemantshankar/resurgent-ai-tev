package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.enrichment.EnrichmentReport.Cell;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.CellRole;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Region;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.RegionPurpose;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.TypeMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.junit.jupiter.api.Test;

class RegionQaValidatorTest {

    @Test
    void cleanPartitionHasNoProblems() {
        Set<String> filled = Set.of("A1", "B1", "A2", "B2");
        EnrichmentReport report = report(List.of(
                region("required", "A1:B2", RegionPurpose.REQUIRED, filled)));
        WorksheetSnapshot sheet = new WorksheetSnapshot(filled, Map.of());

        EnrichmentReport validated = new RegionQaValidator().validate(sheet, report);

        assertThat(validated.problems()).isEmpty();
    }

    @Test
    void overlappingAssignmentsNameTheCellAndBothRegions() {
        EnrichmentReport report = report(List.of(
                region("left", "A1:B2", RegionPurpose.REQUIRED, Set.of("B2")),
                region("right", "B1:C2", RegionPurpose.REQUIRED, Set.of("B2"))));
        WorksheetSnapshot sheet = new WorksheetSnapshot(Set.of("B2"), Map.of());

        EnrichmentReport validated = new RegionQaValidator().validate(sheet, report);

        assertThat(validated.problems()).containsExactly(new EnrichmentReport.Problem(
                EnrichmentReport.ProblemCode.OVERLAP,
                "Filled cell B2 is assigned to multiple regions",
                List.of("B2"),
                List.of("left", "right")));
        assertThat(validated.regions()).isEqualTo(report.regions());
    }

    @Test
    void filledCellOutsideEveryRegionIsUnassigned() {
        EnrichmentReport report = report(List.of(
                region("table", "A1:B2", RegionPurpose.REQUIRED, Set.of("A1"))));
        WorksheetSnapshot sheet = new WorksheetSnapshot(Set.of("A1", "D4"), Map.of());

        EnrichmentReport validated = new RegionQaValidator().validate(sheet, report);

        assertThat(validated.problems()).containsExactly(new EnrichmentReport.Problem(
                EnrichmentReport.ProblemCode.UNASSIGNED_CELL,
                "Filled cell D4 is outside every region",
                List.of("D4"),
                List.of()));
    }

    @Test
    void regionsOnlyPartitionPassesWithoutPerCellRoles() {
        EnrichmentReport report = new EnrichmentReport(
                EnrichmentReport.VERSION,
                "fixture.xlsx",
                "Project Cost",
                "/tmp/fixture-redacted.xlsx",
                "/tmp/fixture-unhidden.xlsx",
                "fixture-enrichment-model",
                LlmEnrichmentAdapter.PROMPT_VERSION_REGIONS_ONLY,
                new TypeMenu(List.of("Civil Cost"), List.of()),
                List.of(new Region(
                        "table",
                        "A1:B2",
                        "table",
                        "Civil Cost",
                        RegionPurpose.REQUIRED,
                        List.of(),
                        List.of())),
                List.of());
        WorksheetSnapshot sheet = new WorksheetSnapshot(Set.of("A1", "B2"), Map.of());

        EnrichmentReport validated = new RegionQaValidator().validate(sheet, report);

        assertThat(validated.problems()).isEmpty();
    }

    @Test
    void filledCellInsideBoundsStillNeedsAPerCellRole() {
        EnrichmentReport report = report(List.of(
                new Region(
                        "table",
                        "A1:B2",
                        "table",
                        "Civil Cost",
                        RegionPurpose.REQUIRED,
                        List.of(new Cell(
                                "A1", CellRole.TITLE, null, null, null, null)),
                        List.of())));
        WorksheetSnapshot sheet = new WorksheetSnapshot(Set.of("A1", "B2"), Map.of());

        EnrichmentReport validated = new RegionQaValidator().validate(sheet, report);

        assertThat(validated.problems()).containsExactly(new EnrichmentReport.Problem(
                EnrichmentReport.ProblemCode.UNASSIGNED_CELL,
                "Filled cell B2 has no cell role in region table",
                List.of("B2"),
                List.of("table")));
    }

    @Test
    void requiredFormulaCannotReferenceScratchRegion() {
        Set<String> liveFilled = Set.of("A1", "B2");
        Set<String> scratchFilled = Set.of("E1");
        EnrichmentReport report = report(List.of(
                region("live-table", "A1:B2", RegionPurpose.REQUIRED, liveFilled),
                region("check-island", "E1:F2", RegionPurpose.SCRATCH, scratchFilled)));
        WorksheetSnapshot sheet = new WorksheetSnapshot(
                Set.of("A1", "B2", "E1"),
                Map.of("B2", Set.of("E1")));

        EnrichmentReport validated = new RegionQaValidator().validate(sheet, report);

        assertThat(validated.problems()).containsExactly(new EnrichmentReport.Problem(
                EnrichmentReport.ProblemCode.SCRATCH_REFERENCED_BY_REQUIRED,
                "Required region live-table formula B2 references Scratch region check-island",
                List.of("B2", "E1"),
                List.of("live-table", "check-island")));
        assertThat(validated.regions().get(1).purpose()).isEqualTo(RegionPurpose.SCRATCH);
    }

    @Test
    void blankCellInReportIsRejected() {
        EnrichmentReport report = new EnrichmentReport(
                EnrichmentReport.VERSION,
                "fixture.xlsx",
                "Project Cost",
                "/tmp/fixture-redacted.xlsx",
                "/tmp/fixture-unhidden.xlsx",
                "fixture-enrichment-model",
                "test-prompt",
                new TypeMenu(List.of("Civil Cost"), List.of()),
                List.of(new Region(
                        "table",
                        "A1:B2",
                        "table",
                        "Civil Cost",
                        RegionPurpose.REQUIRED,
                        List.of(
                                new Cell("A1", CellRole.TITLE, null, null, null, null),
                                new Cell("B1", CellRole.ANNOTATION, null, null, null, null)),
                        List.of())),
                List.of());
        WorksheetSnapshot sheet = new WorksheetSnapshot(Set.of("A1"), Map.of());

        EnrichmentReport validated = new RegionQaValidator().validate(sheet, report);

        assertThat(validated.problems()).containsExactly(new EnrichmentReport.Problem(
                EnrichmentReport.ProblemCode.BLANK_CELL_IN_REPORT,
                "Cell B1 is blank and must not appear in region table",
                List.of("B1"),
                List.of("table")));
    }

    private static EnrichmentReport report(List<Region> regions) {
        return new EnrichmentReport(
                EnrichmentReport.VERSION,
                "fixture.xlsx",
                "Project Cost",
                "/tmp/fixture-redacted.xlsx",
                "/tmp/fixture-unhidden.xlsx",
                "fixture-enrichment-model",
                "test-prompt",
                new TypeMenu(List.of("Civil Cost"), List.of()),
                regions,
                List.of());
    }

    private static Region region(
            String id, String bounds, RegionPurpose purpose, Set<String> filled) {
        return new Region(
                id,
                bounds,
                id,
                "Civil Cost",
                purpose,
                cells(bounds, filled),
                List.of());
    }

    private static List<Cell> cells(String bounds, Set<String> filled) {
        CellRangeAddress range = CellRangeAddress.valueOf(bounds);
        List<Cell> cells = new ArrayList<>();
        for (int row = range.getFirstRow(); row <= range.getLastRow(); row++) {
            for (int column = range.getFirstColumn(); column <= range.getLastColumn(); column++) {
                String address = CellReference.convertNumToColString(column) + (row + 1);
                if (!filled.contains(address)) {
                    continue;
                }
                cells.add(new Cell(
                        address,
                        CellRole.ANNOTATION,
                        null,
                        null,
                        null,
                        null));
            }
        }
        return List.copyOf(cells);
    }
}
