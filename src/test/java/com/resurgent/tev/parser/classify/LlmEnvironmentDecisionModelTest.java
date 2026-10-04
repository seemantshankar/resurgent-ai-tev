package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmEnvironmentDecisionModelTest {

    @TempDir
    Path tempDir;

    private Map<String, String> envWith(String... lines) throws Exception {
        Path dotenv = tempDir.resolve(".env");
        Files.write(dotenv, java.util.List.of(lines));
        return LlmEnvironment.load(dotenv);
    }

    @Test
    void structuredDecisionModelKeySelectsTheCellDecisionModel() throws Exception {
        Map<String, String> env = envWith("Excel_Structured_Decision_Model_id=liquid/d1");
        assertThat(LlmEnvironment.decisionModelId(env)).isEqualTo("liquid/d1");
    }

    @Test
    void oldCellDecisionKeyStillWorksAndWinsWhenBothAreSet() throws Exception {
        assertThat(LlmEnvironment.decisionModelId(
                envWith("Excel_Enrichment_Cell_decision_model_id=old/model")))
                .isEqualTo("old/model");
        assertThat(LlmEnvironment.decisionModelId(envWith(
                "Excel_Enrichment_Cell_decision_model_id=old/model",
                "Excel_Structured_Decision_Model_id=liquid/d1")))
                .isEqualTo("old/model");
    }

    @Test
    void noKeyMeansNoDecisionModel() throws Exception {
        assertThat(LlmEnvironment.decisionModelId(envWith("OPENROUTER_API_KEY=x"))).isNull();
    }

    // ---- thresholds are per model: they do not carry across models ----------------------------

    @Test
    void aModelWithoutItsOwnThresholdUsesTheGlobalOneThenTheDefault() throws Exception {
        assertThat(LlmEnvironment.decisionMinConfidence(envWith("X=1"), "liquid/d1")).isEqualTo(0.90);
        assertThat(LlmEnvironment.decisionMinConfidence(
                envWith("Excel_Enrichment_Cell_decision_min_confidence=0.85"), "liquid/d1")).isEqualTo(0.85);
    }

    @Test
    void aModelsOwnThresholdBeatsTheGlobalOne() throws Exception {
        Map<String, String> env = envWith(
                "Excel_Enrichment_Cell_decision_min_confidence=0.85",
                "Excel_Enrichment_Cell_decision_min_confidence_by_model="
                        + "liquid/d1=0.92, perplexity/pplx-decider-v1-27b=0.80");

        assertThat(LlmEnvironment.decisionMinConfidence(env, "liquid/d1")).isEqualTo(0.92);
        assertThat(LlmEnvironment.decisionMinConfidence(env, "perplexity/pplx-decider-v1-27b")).isEqualTo(0.80);
        assertThat(LlmEnvironment.decisionMinConfidence(env, "inception/mercury-decide")).isEqualTo(0.85);
    }

    @Test
    void aThresholdForTheUndatedSlugCoversTheDatedBuildAndAnExactSlugWins() throws Exception {
        Map<String, String> env = envWith(
                "Excel_Enrichment_Cell_decision_min_confidence_by_model="
                        + "liquid/d1=0.92,liquid/d1-20260930=0.95,inception/mercury-decide=0.97");

        assertThat(LlmEnvironment.decisionMinConfidence(env, "liquid/d1-20260930")).isEqualTo(0.95);
        assertThat(LlmEnvironment.decisionMinConfidence(env, "liquid/d1-20261101")).isEqualTo(0.92);
        assertThat(LlmEnvironment.decisionMinConfidence(env, "inception/mercury-decide:free")).isEqualTo(0.97);
    }

    @Test
    void anUnusableThresholdIsIgnoredNotAppliedAsZero() throws Exception {
        Map<String, String> env = envWith(
                "Excel_Enrichment_Cell_decision_min_confidence_by_model=liquid/d1=abc,perplexity/x=1.5,other/y=0.8");

        assertThat(LlmEnvironment.decisionMinConfidence(env, "liquid/d1")).isEqualTo(0.90);
        assertThat(LlmEnvironment.decisionMinConfidence(env, "perplexity/x")).isEqualTo(0.90);
        assertThat(LlmEnvironment.decisionMinConfidence(env, "other/y")).isEqualTo(0.80);
    }
}
