package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A cached idempotent hit must surface the prior run's real QA status, not a
 * blanket 'success' — otherwise a permanently-cached partial/failed run would
 * look identical to a clean one on every re-ingest.
 */
class IngestSummaryTest {

    @Test
    void fromExistingRun_surfacesNonSuccessStatusFromCachedMetrics() {
        String metricsJson = "{\"worksheetName\":\"Sheet1\",\"rows\":1,\"cellsIn\":2,"
                + "\"qaStatus\":\"partial\",\"qaFailureReasons\":[\"cell_reconciliation_mismatch\"]}";

        IngestSummary summary = IngestSummary.fromExistingRun(
                "f.xlsx", "hash", 1L, 2L, Path.of("ws.db"), metricsJson);

        assertThat(summary.existingRun()).isTrue();
        assertThat(summary.status()).isEqualTo("partial");
        assertThat(summary.metricsJson()).isEqualTo(metricsJson);
    }

    @Test
    void fromExistingRun_defaultsToSuccessForPreTicket11Metrics() {
        String legacyMetricsJson = "{\"worksheetName\":\"Sheet1\",\"rows\":1,\"cellsIn\":2}";

        IngestSummary summary = IngestSummary.fromExistingRun(
                "f.xlsx", "hash", 1L, 2L, Path.of("ws.db"), legacyMetricsJson);

        assertThat(summary.status()).isEqualTo("success");
    }
}
