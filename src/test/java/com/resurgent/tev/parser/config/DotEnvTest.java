package com.resurgent.tev.parser.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotEnvTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesCommentsQuotesAndBlankLines() throws Exception {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, """
                // OpenRouter credentials
                OPENROUTER_API_KEY="secret-key"

                Excel_Enrichment_Model_id='fixture-enrichment-model'
                # ignored comment
                """);

        assertThat(DotEnv.load(env))
                .containsEntry("OPENROUTER_API_KEY", "secret-key")
                .containsEntry("Excel_Enrichment_Model_id", "fixture-enrichment-model");
    }

    @Test
    void missingFileReturnsEmptyMap() throws Exception {
        assertThat(DotEnv.load(tempDir.resolve("missing.env"))).isEmpty();
    }
}
