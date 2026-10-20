package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.doc.llm.DocLlmClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class StageOutcomeTest {

    @Test
    void statusReturnsCompletedWhenZeroFailures() {
        assertThat(StageOutcome.status(0, 10)).isEqualTo(StageStatus.COMPLETED);
        assertThat(StageOutcome.status(0, 0)).isEqualTo(StageStatus.COMPLETED);
    }

    @Test
    void statusReturnsPartialWhenFailuresOccurButResultsPersisted() {
        assertThat(StageOutcome.status(1, 5)).isEqualTo(StageStatus.PARTIAL);
        assertThat(StageOutcome.status(3, 1)).isEqualTo(StageStatus.PARTIAL);
    }

    @Test
    void statusReturnsFailedWhenFailuresOccurAndZeroResultsPersisted() {
        assertThat(StageOutcome.status(1, 0)).isEqualTo(StageStatus.FAILED);
        assertThat(StageOutcome.status(5, 0)).isEqualTo(StageStatus.FAILED);
    }

    @Test
    void evaluateComputesFailuresAndStatus() {
        List<DocLlmClient.Usage> usage = List.of(
                new DocLlmClient.Usage("m1", 2, 1, 100, 50, 0.01, 200),
                new DocLlmClient.Usage("m2", 1, 0, 50, 20, 0.005, 100));

        StageOutcome.Evaluation eval = StageOutcome.evaluate(usage, 4);
        assertThat(eval.failures()).isEqualTo(1);
        assertThat(eval.status()).isEqualTo(StageStatus.PARTIAL);
        assertThat(eval.statusDb()).isEqualTo("partial");
    }

    @Test
    void stageStatusFromDbResolvesKnownValues() {
        assertThat(StageStatus.fromDb("running")).isEqualTo(StageStatus.RUNNING);
        assertThat(StageStatus.fromDb("completed")).isEqualTo(StageStatus.COMPLETED);
        assertThat(StageStatus.fromDb("partial")).isEqualTo(StageStatus.PARTIAL);
        assertThat(StageStatus.fromDb("failed")).isEqualTo(StageStatus.FAILED);
        assertThat(StageStatus.fromDb(null)).isEqualTo(StageStatus.FAILED);

        assertThatThrownBy(() -> StageStatus.fromDb("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
