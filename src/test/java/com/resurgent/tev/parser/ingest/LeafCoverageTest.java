package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Internal seam for leaf-coverage algebra. Bounding boxes are intentionally unused.
 */
class LeafCoverageTest {

    @Test
    void disjointSets_areAdded() {
        LeafCoverage.Result result = LeafCoverage.compose(List.of(
                member(1, Set.of(10L, 11L), "100"),
                member(2, Set.of(20L, 21L), "50")));
        assertThat(result.relation()).isEqualTo(LeafCoverage.Relation.DISJOINT);
        assertThat(result.amountRegionIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(result.supersededRegionIds()).isEmpty();
        assertThat(result.amount()).isEqualByComparingTo("150");
        assertThat(result.blocksTrust()).isFalse();
    }

    @Test
    void strictSuperset_supersedesSubset() {
        LeafCoverage.Result result = LeafCoverage.compose(List.of(
                member(1, Set.of(10L, 11L), "150"),
                member(2, Set.of(10L), "100")));
        assertThat(result.relation()).isEqualTo(LeafCoverage.Relation.SUPERSET);
        assertThat(result.amountRegionIds()).containsExactly(1L);
        assertThat(result.supersededRegionIds()).containsExactly(2L);
        assertThat(result.amount()).isEqualByComparingTo("150");
        assertThat(result.blocksTrust()).isFalse();
    }

    @Test
    void identicalSets_areDuplicates() {
        LeafCoverage.Result result = LeafCoverage.compose(List.of(
                member(1, Set.of(10L, 11L), "150"),
                member(2, Set.of(11L, 10L), "150")));
        assertThat(result.relation()).isEqualTo(LeafCoverage.Relation.IDENTICAL);
        assertThat(result.amountRegionIds()).containsExactly(1L);
        assertThat(result.duplicateRegionIds()).containsExactly(2L);
        assertThat(result.amount()).isEqualByComparingTo("150");
        assertThat(result.blocksTrust()).isFalse();
    }

    @Test
    void partialOverlap_blocksTrustAndDoesNotAdd() {
        LeafCoverage.Result result = LeafCoverage.compose(List.of(
                member(1, Set.of(10L, 11L), "150"),
                member(2, Set.of(11L, 12L), "80")));
        assertThat(result.relation()).isEqualTo(LeafCoverage.Relation.PARTIAL);
        assertThat(result.amountRegionIds()).containsExactly(1L);
        assertThat(result.blocksTrust()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo("150");
    }

    @Test
    void formulaReachableLeaves_proveContainment() {
        Map<Long, List<Long>> precedents = Map.of(
                99L, List.of(10L, 11L));
        Set<Long> expanded = LeafCoverage.expand(Set.of(), 99L, precedents, Set.of(10L, 11L, 20L));
        assertThat(expanded).containsExactlyInAnyOrder(10L, 11L);

        LeafCoverage.Result result = LeafCoverage.compose(List.of(
                member(1, Set.of(10L, 11L), "150"),
                member(2, expanded, "150")));
        assertThat(result.relation()).isEqualTo(LeafCoverage.Relation.IDENTICAL);
        assertThat(result.amount()).isEqualByComparingTo("150");
    }

    private static LeafCoverage.Member member(long regionId, Set<Long> coverage, String amount) {
        return new LeafCoverage.Member(regionId, coverage, new BigDecimal(amount));
    }
}
