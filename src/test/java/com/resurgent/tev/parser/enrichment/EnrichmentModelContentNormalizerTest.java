package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EnrichmentModelContentNormalizerTest {

    @Test
    void stripsMarkdownJsonFence() {
        String json = """
                ```json
                {"version":"enrichment-report-v1"}
                ```
                """;

        assertThat(EnrichmentModelContentNormalizer.normalize(json))
                .isEqualTo("{\"version\":\"enrichment-report-v1\"}");
    }

    @Test
    void leavesRawJsonUntouched() {
        assertThat(EnrichmentModelContentNormalizer.normalize("{\"version\":\"enrichment-report-v1\"}"))
                .isEqualTo("{\"version\":\"enrichment-report-v1\"}");
    }

    @Test
    void dropsFreeTextProblemStrings() {
        String json = """
                {"version":"enrichment-report-v1","problems":["note only",{"code":"overlap","message":"x","cells":["A1"],"regionIds":["r1"]}]}
                """;

        String normalized = EnrichmentModelContentNormalizer.normalize(json);

        assertThat(normalized).doesNotContain("note only");
        assertThat(normalized).contains("\"code\":\"overlap\"");
    }
}
