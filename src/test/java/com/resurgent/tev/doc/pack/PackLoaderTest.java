package com.resurgent.tev.doc.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T2 residual: {@link PackLoader} validation contract (S2.1). Valid seed packs
 * load; invalid packs fail with actionable errors; {@code toExtractSchema()}
 * lists every pack field.
 */
class PackLoaderTest {

    @TempDir
    Path tempDir;

    private static DocTypePack loadYaml(String yaml) throws Exception {
        return PackLoader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test-pack");
    }

    private static final String MINIMAL_VALID = """
            code: demo.sample
            version: 1.0.0
            family: certificate
            file_role: certificate
            identity_signals:
              any_of: ["Net Worth"]
            fields:
              - key: net_worth
                path: economic > Promoter Net Worth > Net Worth
                path_root: economic
                value_kind: money
                required: true
            """;

    @Test
    void seedPacksLoadFromPackDirAndClasspath() throws Exception {
        List<DocTypePack> seed = PackLoader.loadSeedPacks();
        assertThat(seed).hasSize(3);
        assertThat(seed.stream().map(DocTypePack::code))
                .containsExactlyInAnyOrder(
                        "identity.promoter_net_worth", "quotation.vendor", "agreement.ppa");

        Path packDir = tempDir.resolve("packs");
        Files.createDirectories(packDir);
        Files.writeString(packDir.resolve("a.yaml"), Files.readString(
                Path.of("src/main/resources/docpacks/agreement.ppa.yaml")));
        Files.writeString(packDir.resolve("b.yaml"), Files.readString(
                Path.of("src/main/resources/docpacks/quotation.vendor.yaml")));
        List<DocTypePack> fromDir = PackLoader.loadDirectory(packDir);
        assertThat(fromDir).hasSize(2);
        assertThat(fromDir.stream().map(DocTypePack::code))
                .containsExactly("agreement.ppa", "quotation.vendor");

        for (DocTypePack pack : seed) {
            assertThat(pack.identitySignals()).isNotEmpty();
            assertThat(pack.fields()).isNotEmpty();
            assertThat(pack.version()).matches("\\d+\\.\\d+\\.\\d+");
        }
    }

    @Test
    void invalidSemverVersionFailsWithActionableError() {
        String yaml = MINIMAL_VALID.replace("version: 1.0.0", "version: v1");
        assertThatThrownBy(() -> loadYaml(yaml))
                .isInstanceOf(PackLoader.PackValidationException.class)
                .hasMessageContaining("version")
                .hasMessageContaining("semver");
    }

    @Test
    void invalidPathRootFailsWithActionableError() {
        String yaml = MINIMAL_VALID.replace("path_root: economic", "path_root: balance_sheet");
        assertThatThrownBy(() -> loadYaml(yaml))
                .isInstanceOf(PackLoader.PackValidationException.class)
                .hasMessageContaining("path_root")
                .hasMessageContaining("balance_sheet");
    }

    @Test
    void duplicateFieldKeyFailsWithActionableError() {
        String yaml = MINIMAL_VALID + """
                  - key: net_worth
                    path: economic > Promoter Net Worth > Net Worth
                    path_root: economic
                    value_kind: money
                    required: false
                """;
        assertThatThrownBy(() -> loadYaml(yaml))
                .isInstanceOf(PackLoader.PackValidationException.class)
                .hasMessageContaining("duplicate field key")
                .hasMessageContaining("net_worth");
    }

    @Test
    void entityLikeFieldKeyFailsWithActionableError() {
        String yaml = MINIMAL_VALID.replace("key: net_worth", "key: sharma");
        assertThatThrownBy(() -> loadYaml(yaml))
                .isInstanceOf(PackLoader.PackValidationException.class)
                .hasMessageContaining("sharma")
                .hasMessageContaining("label");

        String pan = MINIMAL_VALID.replace("key: net_worth", "key: ABCDE1234F");
        assertThatThrownBy(() -> loadYaml(pan))
                .isInstanceOf(PackLoader.PackValidationException.class)
                .hasMessageContaining("ABCDE1234F");
    }

    @Test
    void toExtractSchemaListsEveryPackField() throws Exception {
        DocTypePack pack = loadYaml(MINIMAL_VALID);
        JsonNode schema = pack.toExtractSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        JsonNode props = schema.path("properties");
        assertThat(props.has("net_worth")).isTrue();
        assertThat(props.path("net_worth").path("value_kind").asText()).isEqualTo("money");
        assertThat(props.path("net_worth").path("type").asText()).isEqualTo("number");
        List<String> required = new java.util.ArrayList<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        assertThat(required).containsExactly("net_worth");
    }

    @Test
    void seedPackSchemasListEveryField() throws Exception {
        for (DocTypePack pack : PackLoader.loadSeedPacks()) {
            JsonNode props = pack.toExtractSchema().path("properties");
            for (PackField field : pack.fields()) {
                assertThat(props.has(field.key()))
                        .as("schema property for " + pack.code() + "#" + field.key())
                        .isTrue();
                assertThat(props.path(field.key()).path("value_kind").asText())
                        .isEqualTo(field.valueKind().wireName());
            }
            // quotation pack declares line_items rows
            if (pack.tables().stream().anyMatch(t -> "line_items".equals(String.valueOf(t.get("name"))))) {
                assertThat(props.has("line_items")).isTrue();
            }
        }
    }

    @Test
    void missingFieldsListFailsWithActionableError() {
        String yaml = """
                code: demo.empty
                version: 1.0.0
                family: certificate
                file_role: certificate
                fields: []
                """;
        assertThatThrownBy(() -> loadYaml(yaml))
                .isInstanceOf(PackLoader.PackValidationException.class)
                .hasMessageContaining("fields");
    }

    @Test
    void unknownValueKindFailsWithActionableError() {
        String yaml = MINIMAL_VALID.replace("value_kind: money", "value_kind: crypto");
        assertThatThrownBy(() -> loadYaml(yaml))
                .isInstanceOf(PackLoader.PackValidationException.class)
                .hasMessageContaining("value_kind")
                .hasMessageContaining("crypto");
    }
}
