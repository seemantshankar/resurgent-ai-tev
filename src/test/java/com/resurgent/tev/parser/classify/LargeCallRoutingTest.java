package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.CompletionResult;
import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.CompletionsClient;
import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.UsageTotals;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Big calls (region layout, Layer A batches) can try a faster model first; the rest keep the usual order. */
class LargeCallRoutingTest {

    private static final class Recording implements CompletionsClient {
        final String name;
        final List<String> calls;
        final long used;

        Recording(String name, List<String> calls, long used) {
            this.name = name;
            this.calls = calls;
            this.used = used;
        }

        @Override
        public CompletionResult completeJson(String system, String user, int maxCompletionTokens) {
            calls.add(name);
            return CompletionResult.of("{\"ok\":true}");
        }

        @Override
        public CompletionResult completeLayerA(String system, String user, List<String> scheduleFamilies) {
            calls.add(name);
            return CompletionResult.of("{\"ok\":true}");
        }

        @Override
        public UsageTotals usageTotals() {
            return new UsageTotals(used, used * 10, used, used / 100.0, 0);
        }
    }

    @Test
    void layerABatchesGoToTheLargeCallChainAndCellCallsDoNot() {
        List<String> calls = new ArrayList<>();
        var llm = new OpenRouterClassifierLlm(new Recording("ordinary", calls, 3), new Recording("large", calls, 2));

        llm.classifyLayerAJson("prompt", 4_096);
        llm.classifyCellJson("system", "user", 1_024);

        assertThat(calls).containsExactly("large", "ordinary");
    }

    @Test
    void withoutALargeChainEveryCallUsesTheOrdinaryOne() {
        List<String> calls = new ArrayList<>();
        var llm = new OpenRouterClassifierLlm(new Recording("ordinary", calls, 3));

        llm.classifyLayerAJson("prompt", 4_096);

        assertThat(calls).containsExactly("ordinary");
        assertThat(llm.usageTotals().calls()).isEqualTo(3);
    }

    @Test
    void usageCountsBothChains() {
        var llm = new OpenRouterClassifierLlm(
                new Recording("ordinary", new ArrayList<>(), 3), new Recording("large", new ArrayList<>(), 2));
        assertThat(llm.usageTotals().calls()).isEqualTo(5);
        assertThat(llm.usageTotals().completionTokens()).isEqualTo(5);
    }
}
