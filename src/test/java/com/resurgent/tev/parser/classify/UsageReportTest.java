package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class UsageReportTest {

    private static LlmStats.UsageRow row(String stage, String model, long calls, long failed, long prompt,
            long completion, Double cost, long costMissing) {
        return new LlmStats.UsageRow(stage, model, calls, failed, 0, prompt, completion, cost, costMissing, 0);
    }

    @Test
    void theTotalCoversEveryModelIncludingTheDecisionModel() {
        List<String> lines = UsageReport.lines(List.of(
                row("layer-a", "inception/mercury-2.5", 75, 8, 1000, 200, 0.045, 0),
                row("layer-b", "liquid/d1", 191, 0, 114000, 0, 0.0044, 0),
                row("layer-b", "openai/gpt-6-luna", 9, 0, 5000, 900, 0.0088, 0)));

        assertThat(lines.get(0))
                .startsWith("LLM_COST_TOTAL")
                .contains("calls=275").contains("failed=8").contains("cost_usd=0.058200").contains("cost_missing=0");
        assertThat(lines).anySatisfy(l -> assertThat(l)
                .contains("stage=layer-b").contains("model=liquid/d1").contains("calls=191").contains("cost_usd=0.004400"));
        assertThat(lines).hasSize(4);
    }

    @Test
    void aCallWhoseCostWasNotReportedIsCountedAndTheTotalSaysSo() {
        List<String> lines = UsageReport.lines(List.of(
                row("layer-b", "a/model", 10, 0, 1, 1, 0.01, 4),
                row("layer-b", "b/model", 3, 0, 1, 1, null, 3)));

        assertThat(lines.get(0)).contains("cost_usd=0.010000").contains("cost_missing=7").contains("partial");
        assertThat(lines.get(2)).contains("model=b/model").contains("cost_usd=unknown");
    }

    @Test
    void noCallsMeansNoReport() {
        assertThat(UsageReport.lines(List.of())).isEmpty();
    }

    @Test
    void cachedPromptTokensAreReportedPerLineAndInTotal() {
        List<String> lines = UsageReport.lines(List.of(
                new LlmStats.UsageRow("layer-a", "openai/gpt-6-luna", 5, 0, 0, 10_000, 100, 0.01, 0, 0, 6_000),
                new LlmStats.UsageRow("layer-b", "openai/gpt-6-luna", 5, 0, 0, 20_000, 100, 0.02, 0, 0, 0)));

        assertThat(lines.get(0)).contains("prompt_tokens=30000").contains("cached_tokens=6000");
        assertThat(lines.get(1)).contains("stage=layer-a").contains("cached_tokens=6000");
        assertThat(lines.get(2)).contains("stage=layer-b").contains("cached_tokens=0");
    }

    @Test
    void aProvidersCachedTokenCountIsReadFromTheUsageBlock() {
        assertThat(OpenRouterClassifierLlm.HttpCompletionsClient.cachedTokens(
                "{\"usage\":{\"prompt_tokens\":6331,\"prompt_tokens_details\":{\"cached_tokens\":6311}}}"))
                .isEqualTo(6311);
        assertThat(OpenRouterClassifierLlm.HttpCompletionsClient.cachedTokens("{\"usage\":{\"prompt_tokens\":10}}"))
                .isZero();
        assertThat(OpenRouterClassifierLlm.HttpCompletionsClient.cachedTokens("not json")).isZero();
    }

    @Test
    void cachedTokensAccumulatePerModelAndLandInTheRunStats() {
        LlmStats stats = new LlmStats();
        stats.enterStage("layer-a");
        stats.recordCall("m", 1000, 10, 400, 0.001, 5);
        stats.recordCall("m", 1000, 10, 0, 0.001, 5);

        assertThat(stats.usageRows()).singleElement()
                .satisfies(r -> assertThat(r.cachedPromptTokens()).isEqualTo(400));
        assertThat(stats.stat("layer-a", "cached_prompt_tokens")).isEqualTo(400.0);
    }
}
