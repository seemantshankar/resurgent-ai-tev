package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.CompletionResult;
import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.CompletionsClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayerABatchingTest {

    private static List<List<Integer>> split(List<Integer> weights, int maxItems, long budget) {
        return LayerABatching.split(weights, Integer::longValue, maxItems, budget);
    }

    @Test
    void smallCandidatesFillABatchUpToTheCandidateLimit() {
        assertThat(split(List.of(1, 1, 1, 1, 1), 2, 100))
                .containsExactly(List.of(1, 1), List.of(1, 1), List.of(1));
    }

    @Test
    void aBatchStopsWhereTheNextCandidateWouldBustTheSizeBudget() {
        assertThat(split(List.of(40, 40, 40, 40, 40), 25, 100))
                .containsExactly(List.of(40, 40), List.of(40, 40), List.of(40));
    }

    @Test
    void aCandidateOverTheBudgetGoesAloneAndOrderIsKept() {
        assertThat(split(List.of(10, 500, 10, 10), 25, 100))
                .containsExactly(List.of(10), List.of(500), List.of(10, 10));
    }

    @Test
    void nothingToSplitIsNoBatches() {
        assertThat(split(List.of(), 25, 100)).isEmpty();
    }

    @Test
    void theReplyBudgetGrowsWithTheCandidatesButIsBounded() {
        assertThat(OpenRouterClassifierLlm.layerAMaxCompletionTokens(1)).isEqualTo(4_096);
        assertThat(OpenRouterClassifierLlm.layerAMaxCompletionTokens(25)).isEqualTo(20_000);
        assertThat(OpenRouterClassifierLlm.layerAMaxCompletionTokens(500)).isEqualTo(24_576);
    }

    /** Answers truncated until it is given enough room, and records the caps it was asked with. */
    private static final class NeedsRoom implements CompletionsClient {
        final int needed;
        final List<Integer> caps = new ArrayList<>();

        NeedsRoom(int needed) {
            this.needed = needed;
        }

        @Override
        public CompletionResult completeJson(String system, String user, int max) {
            caps.add(max);
            return max >= needed
                    ? new CompletionResult("{\"results\":[]}", null, null, null, "stop", false)
                    : new CompletionResult("{\"results\":[{\"about\":\"cut", null, null, null, "length", true);
        }

        @Override
        public CompletionResult completeLayerA(String system, String user, List<String> families) {
            return completeJson(system, user, 0);
        }
    }

    /** A cut-off reply is a request that needs more room, not a model that failed: ask the same model again. */
    @Test
    void aTruncatedBatchReplyIsRetriedOnTheSameModelWithMoreRoom() {
        var client = new NeedsRoom(8_000);

        String json = new OpenRouterClassifierLlm(client).classifyLayerAJson("prompt", 4_096);

        assertThat(json).isEqualTo("{\"results\":[]}");
        assertThat(client.caps).containsExactly(4_096, 8_192);
    }

    @Test
    void aReplyStillCutOffAfterTheRetryIsAFailureOfThatModel() {
        var client = new NeedsRoom(1_000_000);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new OpenRouterClassifierLlm(client).classifyLayerAJson("prompt", 4_096))
                .hasMessageContaining("truncated");
        assertThat(client.caps).hasSize(2);
    }
}
