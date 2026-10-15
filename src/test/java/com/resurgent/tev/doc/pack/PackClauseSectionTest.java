package com.resurgent.tev.doc.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M3-08 pack clause section (issue #141): packs declare the clause types bound for
 * their document type and whether open extraction runs. The section is optional —
 * packs that do not declare clauses round-trip byte-identically without it.
 */
class PackClauseSectionTest {

    private static DocTypePack loadYaml(String yaml) throws Exception {
        return PackLoader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test-pack");
    }

    private static final String PACK_WITH_CLAUSES = """
            code: demo.agreement
            version: 1.0.0
            family: agreement
            file_role: agreement
            fields:
              - key: tariff
                path: commercial > Tariff
                path_root: commercial
                value_kind: money
                required: true
            clauses:
              open_extraction: true
              types:
                - key: termination
                  label: Termination
                  parent_family: termination
                  aliases: ["Termination", "Termination by Procurer"]
                - key: force_majeure
                  label: Force Majeure
                  aliases: ["Force Majeure"]
            """;

    @Test
    void ppaSeedDeclaresItsClauseTypes() throws Exception {
        DocTypePack ppa = PackLoader.loadClasspath("docpacks/agreement.ppa.yaml");
        assertThat(ppa.declaresClauses()).isTrue();
        assertThat(ppa.clauseOpenExtraction()).isTrue();
        assertThat(ppa.clauseTypes()).extracting(PackClauseType::key)
                .contains("termination", "force_majeure");
        PackClauseType termination = ppa.clauseTypes().stream()
                .filter(t -> "termination".equals(t.key()))
                .findFirst().orElseThrow();
        assertThat(termination.label()).isEqualTo("Termination");
        assertThat(termination.parentFamily()).isEqualTo("termination");
        assertThat(termination.aliases()).contains("Termination");
    }

    @Test
    void packsWithoutAClauseSectionDoNotDeclareClauses() throws Exception {
        assertThat(PackLoader.loadClasspath("docpacks/identity.promoter_net_worth.yaml")
                .declaresClauses()).isFalse();
        assertThat(PackLoader.loadClasspath("docpacks/quotation.vendor.yaml")
                .declaresClauses()).isFalse();
    }

    @Test
    void clauseSectionRoundTripsThroughTheYamlWriter() throws Exception {
        DocTypePack pack = loadYaml(PACK_WITH_CLAUSES);
        String yaml = com.resurgent.tev.doc.induce.PackYamlWriter.write(pack);
        DocTypePack reloaded = loadYaml(yaml);
        assertThat(reloaded.declaresClauses()).isTrue();
        assertThat(reloaded.clauseOpenExtraction()).isTrue();
        assertThat(reloaded.clauseTypes()).hasSize(2);
        assertThat(reloaded.clauseTypes().get(0).aliases())
                .containsExactly("Termination", "Termination by Procurer");
        assertThat(reloaded.clauseTypes().get(1).parentFamily()).isNull();
        String again = com.resurgent.tev.doc.induce.PackYamlWriter.write(reloaded);
        assertThat(again).isEqualTo(yaml);
    }

    @Test
    void packWithoutClausesWritesNoClauseSection() throws Exception {
        DocTypePack pack = loadYaml(PACK_WITH_CLAUSES.replaceAll(
                "(?s)clauses:.*", "").strip() + "\n");
        String yaml = com.resurgent.tev.doc.induce.PackYamlWriter.write(pack);
        assertThat(yaml).doesNotContain("clauses:");
        assertThat(loadYaml(yaml).declaresClauses()).isFalse();
    }

    @Test
    void invalidClauseTypeFailsWithActionableError() {
        String duplicate = PACK_WITH_CLAUSES.replace(
                "- key: force_majeure", "- key: termination");
        assertThatThrownBy(() -> loadYaml(duplicate))
                .isInstanceOf(PackLoader.PackValidationException.class)
                .hasMessageContaining("duplicate clause type key")
                .hasMessageContaining("termination");

        String missingLabel = PACK_WITH_CLAUSES.replace("label: Force Majeure", "aliases: [x]");
        assertThatThrownBy(() -> loadYaml(missingLabel))
                .isInstanceOf(PackLoader.PackValidationException.class)
                .hasMessageContaining("label");
    }

    @Test
    void clauseOpenExtractionDefaultsToTrueAndCanBeDisabled() throws Exception {
        DocTypePack defaulted = loadYaml(PACK_WITH_CLAUSES.replace(
                "  open_extraction: true\n", ""));
        assertThat(defaulted.declaresClauses()).isTrue();
        assertThat(defaulted.clauseOpenExtraction()).isTrue();

        DocTypePack closed = loadYaml(PACK_WITH_CLAUSES.replace(
                "open_extraction: true", "open_extraction: false"));
        assertThat(closed.clauseOpenExtraction()).isFalse();
    }

    @Test
    void clauseTypesMayBeEmptyWhenThePackOnlyWantsOpenReading() throws Exception {
        DocTypePack pack = loadYaml("""
                code: demo.open
                version: 1.0.0
                family: agreement
                file_role: agreement
                fields:
                  - key: tariff
                    path: commercial > Tariff
                    path_root: commercial
                    value_kind: money
                    required: true
                clauses:
                  open_extraction: true
                """);
        assertThat(pack.declaresClauses()).isTrue();
        assertThat(pack.clauseTypes()).isEmpty();
        List<String> written = List.of(com.resurgent.tev.doc.induce.PackYamlWriter.write(pack)
                .split("\n"));
        assertThat(written).anyMatch(l -> l.startsWith("clauses:"));
    }
}
