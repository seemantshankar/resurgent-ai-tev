package com.resurgent.tev.parser.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

    @Test
    void nullConfig_returnsEmbeddedDefaults() throws IOException {
        ParserConfig config = ConfigLoader.load((String) null);

        assertThat(config).isEqualTo(ParserConfig.embeddedDefaults());
    }

    @Test
    void emptyConfig_returnsEmbeddedDefaults() throws IOException {
        ParserConfig config = ConfigLoader.load("{}");

        assertThat(config).isEqualTo(ParserConfig.embeddedDefaults());
    }

    @Test
    void validOverride_applies() throws IOException {
        ParserConfig config = ConfigLoader.load("{\"maxFileSizeBytes\": 52428800, \"regionBreakThreshold\": 6, \"classificationEvidenceFloor\": 5}");

        assertThat(config.maxFileSizeBytes()).isEqualTo(52_428_800L);
        assertThat(config.maxSheetCount()).isEqualTo(ParserConfig.embeddedDefaults().maxSheetCount());
        assertThat(config.regionBreakThreshold()).isEqualTo(6);
        assertThat(config.classificationEvidenceFloor()).isEqualTo(5);
    }

    @Test
    void unknownKey_rejected() {
        assertThatThrownBy(() -> ConfigLoader.load("{\"unknownKey\": true}"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("unknown config key: unknownKey");
    }

    @Test
    void disablingSecurityProtection_rejected() {
        assertThatThrownBy(() -> ConfigLoader.load("{\"rejectPasswordProtected\": false}"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("security protection cannot be disabled");
    }

    @Test
    void exceedingHardCeiling_rejected() {
        assertThatThrownBy(() -> ConfigLoader.load(
                "{\"maxFileSizeBytes\": " + (ParserConfig.MAX_FILE_SIZE_BYTES_CEILING + 1) + "}"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("exceeds hard ceiling");
    }

    @Test
    void wrongType_rejected() {
        assertThatThrownBy(() -> ConfigLoader.load("{\"maxFileSizeBytes\": \"large\"}"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("maxFileSizeBytes has invalid type");
    }

    @Test
    void nonPositiveRegionBreakThreshold_rejected() {
        assertThatThrownBy(() -> ConfigLoader.load("{\"regionBreakThreshold\": 0}"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("regionBreakThreshold must be positive");
    }

    @Test
    void nonPositiveClassificationEvidenceFloor_rejected() {
        assertThatThrownBy(() -> ConfigLoader.load("{\"classificationEvidenceFloor\": 0}"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("classificationEvidenceFloor must be positive");
    }
}
