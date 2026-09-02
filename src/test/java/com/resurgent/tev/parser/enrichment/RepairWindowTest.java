package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.enrichment.EnrichmentReport.Problem;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.ProblemCode;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Region;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.RegionPurpose;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.TypeMenu;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepairWindowTest {

    @Test
    void appendix5UnderboxKeepsTitleAndBodyAndDropsAppendix6() {
        EnrichmentReport report = report(List.of(
                region("title", "A4:A7"),
                region("appendix5", "J9:M59"),
                region("appendix6", "A70:M120"),
                region("nfa", "D139:M151")),
                List.of("A12", "D14", "A18", "I59", "A141"));
        Set<String> filled = Set.of(
                "A4", "A12", "D14", "A18", "I59", "J9", "M59",
                "A70", "M120", "D139", "A141", "M151");

        RepairWindow window = RepairWindow.from(report, filled);

        assertThat(window.leftovers()).containsExactlyInAnyOrder("A12", "A18", "A141", "D14", "I59");
        assertThat(window.nearbyRegions())
                .extracting(Region::id)
                .containsExactly("title", "appendix5", "nfa");
        assertThat(window.cropCells())
                .contains("A12", "D14", "A4", "J9", "M59", "D139", "A141")
                .doesNotContain("A70", "M120");
    }

    @Test
    void sideScratchIsNotNearbyAcrossAWideColumnGap() {
        EnrichmentReport report = report(List.of(
                region("appendix5", "J9:M59"),
                region("scratch", "O47:W59")),
                List.of("A12", "I59"));
        Set<String> filled = Set.of("A12", "I59", "J9", "M59", "O47", "W59");

        RepairWindow window = RepairWindow.from(report, filled);

        assertThat(window.nearbyRegions()).extracting(Region::id).containsExactly("appendix5");
        assertThat(window.cropCells()).doesNotContain("O47", "W59");
    }

    private static EnrichmentReport report(List<Region> regions, List<String> leftovers) {
        List<Problem> problems = leftovers.stream()
                .map(cell -> new Problem(
                        ProblemCode.UNASSIGNED_CELL,
                        "Filled cell " + cell + " is outside every region",
                        List.of(cell),
                        List.of()))
                .toList();
        return new EnrichmentReport(
                EnrichmentReport.VERSION,
                "fixture.xlsx",
                "depreciation",
                "/tmp/r.xlsx",
                "/tmp/u.xlsx",
                "model",
                LlmEnrichmentAdapter.PROMPT_VERSION,
                new TypeMenu(List.of("Depreciation"), List.of()),
                regions,
                problems);
    }

    private static Region region(String id, String bounds) {
        return new Region(
                id,
                bounds,
                id,
                "Depreciation",
                RegionPurpose.REQUIRED,
                List.of(),
                List.of());
    }
}
