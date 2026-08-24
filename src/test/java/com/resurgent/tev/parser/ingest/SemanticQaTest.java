package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticQaTest {

    @Test
    void pendingAccountedWork_doesNotFail() {
        SemanticQaStats stats = new SemanticQaStats(0, 0, 0, 0, 0, 0, 0);
        assertThat(SemanticQa.reasons(stats, List.of(), Set.of())).isEmpty();
    }

    @Test
    void unaccountedMapping_fails() {
        SemanticQaStats stats = new SemanticQaStats(1, 0, 0, 0, 0, 0, 0);
        assertThat(SemanticQa.reasons(stats, List.of(), Set.of()))
                .anyMatch(reason -> reason.contains("mapping_unaccounted"));
    }

    @Test
    void contributionArithmeticMismatch_fails() {
        SemanticQaStats stats = new SemanticQaStats(0, 0, 1, 0, 0, 0, 0);
        assertThat(SemanticQa.reasons(stats, List.of(), Set.of()))
                .anyMatch(reason -> reason.contains("contribution_arithmetic_mismatch"));
    }

    @Test
    void analystTrustWithoutExactDecision_fails() {
        CostHeadTrust report = new CostHeadTrust(
                "CIVIL", "trusted", "analyst", BigDecimal.ONE, "rs", "INR",
                1.0, List.of(), "Accepted", "fp-1", List.of());
        SemanticQaStats stats = new SemanticQaStats(0, 0, 0, 0, 0, 0, 0);
        assertThat(SemanticQa.reasons(stats, List.of(report), Set.of()))
                .contains("analyst_trust_without_decision");
        assertThat(SemanticQa.reasons(stats, List.of(report), Set.of("fp-1"))).isEmpty();
    }
}
