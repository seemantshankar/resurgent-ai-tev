package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmEnvironmentTest {

    @TempDir
    Path tempDir;

    @Test
    void dotenvSkipsSlashSlashCommentsAndReadsModelId() throws Exception {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, """
                // Openrouter Credentials
                # ignored
                Excel_Enrichment_Model_id=z-ai/glm-5.3-flash
                OTHER=1
                """);
        var loaded = LlmEnvironment.load(env);
        assertThat(loaded.get("Excel_Enrichment_Model_id")).isNotBlank();
        assertThat(loaded.get("OTHER")).isEqualTo("1");
        assertThat(loaded.values().toString()).doesNotContain("//");
    }
}
