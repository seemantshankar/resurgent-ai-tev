package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FixtureExtractClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void loadsSidecarAndReturnsCitedCandidates() throws Exception {
        FixtureExtractClient client = new FixtureExtractClient();
        Path sidecar = Path.of("src/test/resources/docfixtures/golden_ppa.expected.json");
        if (!sidecar.toFile().exists()) {
            sidecar = Path.of("docfixtures/golden_ppa.expected.json");
        }
        client.loadSidecar(sidecar);

        ExtractResult result = client.extractFieldSchema(
                new ExtractRequest(
                        Path.of("."),
                        Path.of("golden_ppa.pdf"),
                        java.util.List.of(1),
                        "en",
                        "agreement.ppa",
                        "extract fields",
                        java.util.Map.of()),
                FixtureExtractClient.textSchema("seller_name", "tariff", "tenor"));

        assertThat(result.extractorId()).isEqualTo(FixtureExtractClient.ID);
        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts().stream().map(FactCandidate::key))
                .contains("seller_name", "tariff", "tenor");
        FactCandidate tariff = result.facts().stream()
                .filter(f -> f.key().equals("tariff"))
                .findFirst()
                .orElseThrow();
        assertThat(tariff.citations()).isNotEmpty();
        assertThat(tariff.citations().get(0).verbatim()).contains("4.50");
        assertThat(result.stats().calls()).isEqualTo(1);
    }

    @Test
    void unknownDocTypeYieldsEmptyFacts() {
        FixtureExtractClient client = new FixtureExtractClient();
        ExtractResult result = client.extractFieldSchema(
                new ExtractRequest(
                        Path.of("."),
                        Path.of("x.pdf"),
                        java.util.List.of(),
                        "en",
                        "does.not.exist",
                        null,
                        java.util.Map.of()),
                MAPPER.createObjectNode());
        assertThat(result.facts()).isEmpty();
    }

    @Test
    void pageSelectionDropsFactsCitedOutsideTheRange() {
        FixtureExtractClient client = new FixtureExtractClient();
        com.fasterxml.jackson.databind.node.ObjectNode fixture = MAPPER.createObjectNode();
        fixture.put("doc_type_code", "demo");
        com.fasterxml.jackson.databind.node.ObjectNode fields = fixture.putObject("fields");
        com.fasterxml.jackson.databind.node.ObjectNode onPage1 = fields.putObject("a");
        onPage1.put("value", "one");
        onPage1.put("quote", "one");
        onPage1.put("page", 1);
        com.fasterxml.jackson.databind.node.ObjectNode onPage3 = fields.putObject("b");
        onPage3.put("value", "three");
        onPage3.put("quote", "three");
        onPage3.put("page", 3);
        client.putFixture("demo", fixture);

        ExtractResult result = client.extractFieldSchema(
                new ExtractRequest(
                        Path.of("."),
                        Path.of("x.pdf"),
                        java.util.List.of(1),
                        "en",
                        "demo",
                        null,
                        java.util.Map.of()),
                FixtureExtractClient.textSchema("a", "b"));

        assertThat(result.facts()).extracting(FactCandidate::key).containsExactly("a");
    }
}
