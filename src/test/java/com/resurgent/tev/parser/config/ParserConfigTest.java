package com.resurgent.tev.parser.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ParserConfigTest {

    @Test
    void embeddedDefaultsAreReasonable() {
        ParserConfig config = ParserConfig.embeddedDefaults();

        assertThat(config.maxFileSizeBytes()).isEqualTo(100L * 1024 * 1024);
        assertThat(config.maxSheetCount()).isEqualTo(200);
        assertThat(config.maxRowCount()).isEqualTo(1_000_000);
        assertThat(config.maxColumnCount()).isEqualTo(16_384);
        assertThat(config.maxCellCount()).isEqualTo(5_000_000L);
        assertThat(config.maxZipExpansionRatio()).isEqualTo(100);
        assertThat(config.xlsEnabled()).isFalse();
        assertThat(config.rejectPasswordProtected()).isTrue();
        assertThat(config.rejectActiveXOleDde()).isTrue();
        assertThat(config.classificationEvidenceFloor()).isEqualTo(3);
    }

    @Test
    void configHashIsDeterministic() {
        ParserConfig a = ParserConfig.embeddedDefaults();
        ParserConfig b = ParserConfig.embeddedDefaults();

        assertThat(a.configHash()).isEqualTo(b.configHash());
    }

    @Test
    void configHashChangesOnEffectiveDifference() {
        ParserConfig defaults = ParserConfig.embeddedDefaults();
        ParserConfig larger = new ParserConfig(
                defaults.maxFileSizeBytes() + 1,
                defaults.maxSheetCount(),
                defaults.maxRowCount(),
                defaults.maxColumnCount(),
                defaults.maxCellCount(),
                defaults.maxZipExpansionRatio(),
                defaults.xlsEnabled(),
                defaults.rejectPasswordProtected(),
                defaults.rejectActiveXOleDde(),
                defaults.classificationEvidenceFloor());

        assertThat(larger.configHash()).isNotEqualTo(defaults.configHash());
    }

    @Test
    void configHashIncludesVersionedRegionWeights() {
        assertThat(RegionWeights.defaults().contentHash()).hasSize(64);
        assertThat(ParserConfig.embeddedDefaults().configHash()).hasSize(64);
    }

    @Test
    void configHashChangesWhenClassificationFloorChanges() {
        ParserConfig defaults = ParserConfig.embeddedDefaults();
        ParserConfig stricter = new ParserConfig(defaults.maxFileSizeBytes(), defaults.maxSheetCount(),
                defaults.maxRowCount(), defaults.maxColumnCount(), defaults.maxCellCount(),
                defaults.maxZipExpansionRatio(), defaults.xlsEnabled(), defaults.rejectPasswordProtected(),
                defaults.rejectActiveXOleDde(),
                defaults.classificationEvidenceFloor() + 1);

        assertThat(stricter.configHash()).isNotEqualTo(defaults.configHash());
    }
}
