package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit: {@link UnboundReason}. The set is pinned here rather than by a database
 * CHECK, so adding a reason is a code change and a test change, not a migration.
 */
class UnboundReasonTest {

    @Test
    void theReasonSetIsExactlyTheDocumentedOne() {
        assertThat(UnboundReason.wireNames()).containsExactlyInAnyOrder(
                "untypable",
                "external_dependency",
                "broken_dependency",
                "range_truncated",
                "no_label",
                "ambiguous_label",
                "kind_conflict",
                "scale_conflict",
                "non_money_group",
                "driver_only",
                "cycle",
                "llm_declined",
                "llm_unavailable");
    }

    @Test
    void wireNamesRoundTrip() {
        for (UnboundReason reason : UnboundReason.values()) {
            assertThat(UnboundReason.fromWire(reason.wireName())).isEqualTo(reason);
        }
        assertThat(UnboundReason.fromWire(null)).isNull();
        assertThat(UnboundReason.fromWire("  ")).isNull();
    }
}
