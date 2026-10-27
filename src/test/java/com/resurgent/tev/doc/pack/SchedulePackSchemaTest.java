package com.resurgent.tev.doc.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.extract.ValueKind;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Record schedules and period matrices compile as row objects whose key, entity,
 * site, and period columns are marked so extraction can emit one fact per cell.
 */
class SchedulePackSchemaTest {

    private static final String SCHEDULE_PACK = """
            code: demo.schedule
            version: 1.0.0
            family: legal
            file_role: land_document
            fields:
              - key: grantor
                path: identity > Legal Name
                path_root: identity
                value_kind: text
                required: true
            tables:
              - name: parcels
                path: technical > Site > Parcel
                path_root: technical
                key_column: survey_no
                entity_key: owner
                site_key: village
                fields:
                  - key: survey_no
                    value_kind: text
                  - key: owner
                    value_kind: text
                  - key: village
                    value_kind: text
                  - key: area
                    value_kind: quantity
                  - key: consideration
                    value_kind: money
              - name: monthly_generation
                path: technical > Generation > Month
                path_root: technical
                key_column: loss
                period_key: month
                fields:
                  - key: loss
                    value_kind: text
                  - key: month
                    value_kind: text
                  - key: glob_hor
                    value_kind: quantity
            """;

    @Test
    void recordScheduleAndPeriodMatrixMarkRowKeyEntitySiteAndPeriodColumns() throws Exception {
        DocTypePack pack = PackLoader.load(
                new ByteArrayInputStream(SCHEDULE_PACK.getBytes(StandardCharsets.UTF_8)),
                "schedule-pack");
        JsonNode props = pack.toExtractSchema().path("properties");

        JsonNode parcelCols = props.path("parcels").path("items").path("properties");
        assertThat(props.path("parcels").path("type").asText()).isEqualTo("array");
        assertThat(parcelCols.path("survey_no").path("line_key").asBoolean()).isTrue();
        assertThat(parcelCols.path("owner").path("entity").asBoolean()).isTrue();
        assertThat(parcelCols.path("village").path("site").asBoolean()).isTrue();
        assertThat(parcelCols.path("area").path("value_kind").asText()).isEqualTo("quantity");
        assertThat(parcelCols.path("consideration").path("value_kind").asText()).isEqualTo("money");
        assertThat(parcelCols.path("area").path("line_key").asBoolean(false)).isFalse();

        JsonNode monthCols = props.path("monthly_generation").path("items").path("properties");
        assertThat(monthCols.path("loss").path("line_key").asBoolean()).isTrue();
        assertThat(monthCols.path("month").path("period").asBoolean()).isTrue();
        assertThat(monthCols.path("glob_hor").path("value_kind").asText()).isEqualTo("quantity");
        assertThat(props.has("grantor")).isTrue();
    }

    @Test
    void omittedColumnValueKindIsInferredFromJsonTypeAndMoneyCues() {
        // In-memory tables can still omit value_kind (induction before publish).
        // PackLoader YAML now rejects that (#199); SchemaCompiler still fills it.
        DocTypePack pack = new DocTypePack(
                "demo.inferred", "1.0.0", "legal", "land_document",
                List.of(), List.of(),
                List.of(PackField.required("grantor", "identity > Legal Name",
                        "identity", ValueKind.TEXT)),
                List.of(Map.of(
                        "name", "parcels",
                        "path", "technical > Site > Parcel",
                        "path_root", "technical",
                        "key_column", "survey_no",
                        "fields", List.of(
                                Map.of("key", "survey_no", "value_kind", "text"),
                                Map.of("key", "area", "type", "number"),
                                Map.of("key", "consideration", "type", "number", "currency", true),
                                Map.of("key", "titled", "type", "boolean"),
                                Map.of("key", "note", "type", "string")))),
                List.of(), List.of());
        JsonNode cols = pack.toExtractSchema().path("properties")
                .path("parcels").path("items").path("properties");
        assertThat(cols.path("area").path("type").asText()).isEqualTo("number");
        assertThat(cols.path("area").path("value_kind").asText()).isEqualTo("quantity");
        assertThat(cols.path("consideration").path("value_kind").asText()).isEqualTo("money");
        assertThat(cols.path("consideration").path("type").asText()).isEqualTo("number");
        assertThat(cols.path("titled").path("value_kind").asText()).isEqualTo("boolean");
        assertThat(cols.path("note").path("value_kind").asText()).isEqualTo("text");
        assertThat(cols.path("survey_no").path("value_kind").asText()).isEqualTo("text");
    }

    @Test
    void keyColumnThatNamesNoColumnFailsAtLoad() {
        String yaml = SCHEDULE_PACK.replace("key_column: survey_no", "key_column: khasra");
        assertThatThrownBy(() -> PackLoader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                "schedule-pack"))
                .isInstanceOf(PackLoader.PackValidationException.class)
                .hasMessageContaining("khasra");
    }
}
