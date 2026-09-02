package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.enrichment.EnrichmentReport.Region;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.RegionPurpose;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EnrichServiceRepairTest {

    @Test
    void mergeRepairKeepsFrozenRegionsReplacesNearbyAndAddsNewIds() {
        Region frozen = region("far", "Z1:Z1");
        Region nearby = region("sales", "A1:A1");
        Region expanded = region("sales", "A1:B2");
        Region extra = region("scratch", "O1:O1");

        List<Region> merged = EnrichService.mergeRepair(
                List.of(frozen, nearby),
                List.of(expanded, extra),
                Set.of("sales"));

        assertThat(merged).extracting(Region::id).containsExactly("far", "sales", "scratch");
        assertThat(merged.get(1).bounds()).isEqualTo("A1:B2");
    }

    @Test
    void mergeRepairIgnoresPatchIdsThatAreFrozen() {
        Region frozen = region("far", "Z1:Z1");
        Region nearby = region("sales", "A1:A1");
        Region retouchedFrozen = region("far", "Z1:Z9");
        Region expanded = region("sales", "A1:B2");

        List<Region> merged = EnrichService.mergeRepair(
                List.of(frozen, nearby),
                List.of(retouchedFrozen, expanded),
                Set.of("sales"));

        assertThat(merged).extracting(Region::id).containsExactly("far", "sales");
        assertThat(merged.getFirst().bounds()).isEqualTo("Z1:Z1");
    }

    private static Region region(String id, String bounds) {
        return new Region(
                id,
                bounds,
                id,
                "Sales",
                RegionPurpose.REQUIRED,
                List.of(),
                List.of());
    }
}
