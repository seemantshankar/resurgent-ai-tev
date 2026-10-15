package com.resurgent.tev.doc.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * M3-06 (issue #139): the seed DPR pack declares, per chapter topic, which data points
 * to take. Passage topics are pack data — chapter fields must resolve to declared pack
 * fields or row tables, so a bad pack fails at load, never mid-run.
 */
class DprSeedPackTest {

    @Test
    void dprSeedDeclaresPassageTopicsWithChapterFields() throws Exception {
        DocTypePack pack = PackLoader.loadClasspath("docpacks/report.dpr.yaml");

        assertThat(pack.code()).isEqualTo("report.dpr");
        assertThat(pack.documentDateKind()).isEqualTo("report");
        assertThat(pack.passages()).isNotEmpty();

        PackPassage overview = passage(pack, "project_overview");
        assertThat(overview.headingTerms()).anySatisfy(term -> assertThat(term).contains("introduction"));
        assertThat(overview.fields()).contains("land_location", "land_area",
                "survey_numbers", "capacity_mw");

        PackPassage machinery = passage(pack, "plant_and_machinery");
        assertThat(machinery.fields()).containsExactly("plant_and_machinery");

        Set<String> fieldKeys = pack.fields().stream()
                .map(PackField::key).collect(Collectors.toSet());
        Set<String> tableNames = pack.tables().stream()
                .map(t -> String.valueOf(t.get("name"))).collect(Collectors.toSet());
        for (PackPassage passage : pack.passages()) {
            assertThat(passage.fields()).isNotEmpty();
            assertThat(passage.fields()).allSatisfy(key -> assertThat(
                    fieldKeys.contains(key) || tableNames.contains(key))
                    .as("topic %s field %s resolves to a pack field or table", passage.topic(), key)
                    .isTrue());
        }
    }

    @Test
    void dprSeedIsOneOfTheEngineSeeds() throws Exception {
        List<DocTypePack> seeds = PackLoader.loadSeedPacks();
        assertThat(seeds.stream().map(DocTypePack::code)).contains("report.dpr");
    }

    @Test
    void passageTopicReferencingUnknownFieldFailsAtLoad() {
        String yaml = """
                code: report.bad
                version: 1.0.0
                family: report
                file_role: report
                fields:
                  - key: land_area
                    path: technical > Site > Land area
                    path_root: technical
                    value_kind: quantity
                passages:
                  - topic: land
                    heading_terms: [land]
                    fields: [land_area, no_such_field]
                """;
        assertThatThrownBy(() -> PackLoader.load(
                new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "test"))
                .isInstanceOf(PackLoader.PackValidationException.class)
                .hasMessageContaining("no_such_field");
    }

    private static PackPassage passage(DocTypePack pack, String topic) {
        return pack.passages().stream()
                .filter(p -> p.topic().equals(topic))
                .findFirst()
                .orElseThrow();
    }
}
