package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmStatsTest {

    private final LlmStats stats = new LlmStats();

    @BeforeEach
    @AfterEach
    void clean() {
        LlmStats.GLOBAL.reset();
    }

    @Test
    void usageIsKeptPerStageAndModel() {
        stats.enterStage("layer-a");
        stats.recordCall("m/one", 100, 10, 0.01, 500);
        stats.recordCall("m/one", 50, 5, 0.02, 300);
        stats.recordFailedCall("m/one", 40);
        stats.recordFailover("m/one");
        stats.recordCall("m/two", 7, 1, 0.5, 100);
        stats.enterStage("layer-b");
        stats.recordCall("m/one", 1, 1, 0.1, 10);

        var rows = stats.usageRows();

        assertThat(rows).hasSize(3);
        var one = rows.stream().filter(r -> r.stage().equals("layer-a") && r.modelId().equals("m/one")).findFirst().orElseThrow();
        assertThat(one.calls()).isEqualTo(2);
        assertThat(one.failedCalls()).isEqualTo(1);
        assertThat(one.failovers()).isEqualTo(1);
        assertThat(one.promptTokens()).isEqualTo(150);
        assertThat(one.completionTokens()).isEqualTo(15);
        assertThat(one.costUsd()).isEqualTo(0.03, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(one.latencyMsTotal()).isEqualTo(840);
        assertThat(rows).extracting(LlmStats.UsageRow::stage).contains("layer-b");
    }

    @Test
    void callsWithNoReportedCostAreCountedNotSummedAsZero() {
        stats.enterStage("layer-b");
        stats.recordCall("m/free", 10, 1, null, 5);

        var row = stats.usageRows().get(0);

        assertThat(row.costUsd()).isNull();
        assertThat(row.costMissing()).isEqualTo(1);
    }

    @Test
    void addAccumulatesAcrossPassesAndPutReplaces() {
        stats.add("layer-b", "cells_to_chat", 10);
        stats.add("layer-b", "cells_to_chat", 5);
        stats.put("layer-b", "cells_total", 100);
        stats.put("layer-b", "cells_total", 120);
        stats.putText("run", "models_chain", "a -> b");

        assertThat(stats.stat("layer-b", "cells_to_chat")).isEqualTo(15.0);
        assertThat(stats.stat("layer-b", "cells_total")).isEqualTo(120.0);
        assertThat(stats.stats()).extracting(LlmStats.Stat::text).contains("a -> b");
    }

    @Test
    void timingRecordsDurationAndItems() {
        Instant start = Instant.parse("2026-10-03T00:00:00Z");
        stats.timing("layer-a", start, start.plusMillis(1500), 25);

        var timing = stats.timings().get(0);

        assertThat(timing.durationMillis()).isEqualTo(1500);
        assertThat(timing.items()).isEqualTo(25);
    }

    @Test
    void callsOutsideAnyStageAreFiledAsOther() {
        stats.recordCall("m/one", 1, 1, 0.0, 1);

        assertThat(stats.usageRows().get(0).stage()).isEqualTo("other");
    }
}
