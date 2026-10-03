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
}
