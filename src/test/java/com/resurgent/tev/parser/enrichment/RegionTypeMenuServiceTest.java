package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionTypeMenuServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void firstLoadContainsAdrStarterTypesWithoutOther() throws Exception {
        RegionTypeMenuService service =
                new RegionTypeMenuService(tempDir.resolve("workspace.db"));

        List<String> types = service.load();

        assertThat(types).containsExactly(
                "Project Cost",
                "Capital Cost",
                "Civil Cost",
                "Land",
                "Plant and Machinery",
                "P&L",
                "Balance Sheet",
                "Cash Flow",
                "Working Capital",
                "Depreciation",
                "Interest",
                "Sales",
                "Assumptions",
                "IRR",
                "Break-even",
                "CMA",
                "Tax",
                "Manpower",
                "Power");
        assertThat(types).doesNotContain("Other");
    }

    @Test
    void synonymMatchesExistingTypeAndNewTypePersists() throws Exception {
        Path dbPath = tempDir.resolve("workspace.db");
        RegionTypeMenuService service = new RegionTypeMenuService(dbPath);

        RegionTypeNormalizationResult result =
                service.normalizeProposals(List.of("Civil", "Civil Works", "Contingency"));

        assertThat(result.canonicalTypes())
                .containsExactly("Civil Cost", "Civil Cost", "Contingency");
        assertThat(result.newTypesAdded()).containsExactly("Contingency");
        assertThat(result.types()).contains("Civil Cost", "Contingency");
        assertThat(new RegionTypeMenuService(dbPath).load())
                .contains("Contingency")
                .doesNotContain("Civil", "Civil Works");
    }

    @Test
    void otherIsRejectedInsteadOfAddedToMenu() throws Exception {
        RegionTypeMenuService service =
                new RegionTypeMenuService(tempDir.resolve("workspace.db"));

        assertThatThrownBy(() -> service.normalizeProposals(List.of("Other")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Other");

        assertThat(service.load()).doesNotContain("Other");
    }
}
