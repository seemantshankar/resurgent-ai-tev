package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CostHeadMapperTest {

    @Test
    void rankedCodes_ordersCivilAboveLandForCivilWrks() {
        List<String> ranked = CostHeadMapper.rankedCodes("civil wrks");
        assertThat(ranked.getFirst()).isEqualTo("CIVIL");
        assertThat(ranked.indexOf("CIVIL")).isLessThan(ranked.indexOf("LAND"));
    }

    @Test
    void equipment_isAmbiguousExactAlias() {
        List<CostHeadMapper.Proposal> proposals = new CostHeadMapper().map(
                "equipment", 1L, "Capex!A1", null);
        assertThat(proposals).hasSize(2);
        assertThat(proposals).allMatch(CostHeadMapper.Proposal::pending);
        assertThat(proposals).extracting(CostHeadMapper.Proposal::code)
                .containsExactly("KITCHEN_EQUIPMENT", "MISC_EQUIPMENT");
    }
}
