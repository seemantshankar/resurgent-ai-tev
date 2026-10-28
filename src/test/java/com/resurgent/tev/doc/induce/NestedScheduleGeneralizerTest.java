package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.extract.Citation;
import com.resurgent.tev.doc.extract.FactCandidate;
import com.resurgent.tev.doc.extract.ScheduleCells;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.pack.SchemaCompiler;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Shape 1 nested row arrays rewrite into pack {@code tables[]} before consensus
 * filters, so extract can use {@code field_key = table.column}.
 */
class NestedScheduleGeneralizerTest {

    private final Generalizer generalizer = new Generalizer();

    @Test
    void rewritesNestedRowArrayFieldsIntoNamedPackTables() {
        PackMiningClient.MiningResult mined = new PackMiningClient.MiningResult(
                "demo.schedule",
                "legal",
                List.of("Site Schedule"),
                List.of(
                        field("grantor", "identity > Legal Name", "identity", "text",
                                List.of("Grantor")),
                        field("line_items", "economic > Project Cost > Line Item", "economic",
                                "list", List.of("Line Items")),
                        field("line_items.description",
                                "economic > Project Cost > Line Item > Description",
                                "economic", "text", List.of("Description")),
                        field("line_items.amount",
                                "economic > Project Cost > Line Item > Amount",
                                "economic", "quantity", List.of("Amount")),
                        field("amount",
                                "economic > Project Cost > Line Item > Amount",
                                "economic", "quantity", List.of("Amount"))));

        Generalizer.Result result = generalizer.generalize(
                mined, 1, Generalizer.Config.defaults(Set.of()));

        assertThat(result.tables()).hasSize(1);
        Map<String, Object> table = result.tables().getFirst();
        assertThat(table.get("name")).isEqualTo("line_items");
        assertThat(table.get("key_column")).isEqualTo("description");
        assertThat(table.get("path_root")).isEqualTo("economic");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("fields");
        assertThat(columns).extracting(column -> column.get("key"))
                .containsExactly("description", "amount");
        assertThat(columns).extracting(column -> column.get("value_kind"))
                .containsExactly("text", "quantity");
        assertThat(result.packFields()).extracting(PackField::key)
                .containsExactly("grantor")
                .doesNotContain("line_items", "line_items.description", "line_items.amount", "amount");
    }

    @Test
    void generalizedPackYamlCompilesAndMapsThroughScheduleCells() throws Exception {
        Generalizer.Result result = generalizer.generalize(
                nestedLineItems(), 1, Generalizer.Config.defaults(Set.of()));
        DocTypePack pack = packOf(result);
        String yaml = PackYamlWriter.write(pack);
        assertThat(yaml).contains("name: line_items");
        assertThat(yaml).contains("key_column: description");
        assertThat(yaml).contains("value_kind: quantity");

        DocTypePack reloaded = PackLoader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                "induced-schedule");
        JsonNode schema = SchemaCompiler.compile(reloaded);
        JsonNode columns = schema.path("properties").path("line_items").path("items")
                .path("properties");
        assertThat(schema.path("properties").path("line_items").path("type").asText())
                .isEqualTo("array");
        assertThat(columns.path("description").path("line_key").asBoolean()).isTrue();
        assertThat(columns.path("amount").path("value_kind").asText()).isEqualTo("quantity");
        assertThat(reloaded.fields()).extracting(PackField::key).contains("grantor");

        JsonNode row = new ObjectMapper().readTree("""
                {"description": "Widget",
                 "amount": {"value": "4.5", "quote": "Widget 4.5", "page": 2}}
                """);
        List<FactCandidate> facts = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        ScheduleCells.expand("line_items", columns, row, facts, citations);
        assertThat(facts).extracting(FactCandidate::key).containsExactly("line_items.amount");
        assertThat(facts.getFirst().lineKey()).isEqualTo("Widget");
        assertThat(facts.getFirst().valueText()).isEqualTo("4.5");
        assertThat(facts.getFirst().valueKind().wireName()).isEqualTo("quantity");
        assertThat(citations).extracting(Citation::verbatim).contains("Widget 4.5");
    }

    @Test
    void rewritesNestedItemsPropertiesFromMinedJson() {
        PackMiningClient.MiningResult mined = LlmPackMiningClient.interpret("""
                {
                  "suggested_code": "demo.schedule",
                  "family": "legal",
                  "identity_signals": ["Site Schedule"],
                  "fields": [
                    {"key":"grantor","path":"identity > Legal Name","path_root":"identity",
                     "value_kind":"text","required":true,"labels":["Grantor"],
                     "domain_standard":true},
                    {"key":"line_items","path":"economic > Project Cost > Line Item",
                     "path_root":"economic","value_kind":"list","required":true,
                     "labels":["Line Items"],"domain_standard":true,
                     "items":{"type":"object","properties":{
                       "description":{"value_kind":"text"},
                       "amount":{"type":"number"}
                     }}}
                  ]
                }
                """, new PackMiningClient.MiningRequest(
                        "general", "land_document", "demo.schedule", List.of()));

        Generalizer.Result result = generalizer.generalize(
                mined, 1, Generalizer.Config.defaults(Set.of()));

        assertThat(result.tables()).hasSize(1);
        Map<String, Object> table = result.tables().getFirst();
        assertThat(table.get("name")).isEqualTo("line_items");
        assertThat(table.get("key_column")).isEqualTo("description");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("fields");
        assertThat(columns).extracting(column -> column.get("key"))
                .containsExactly("description", "amount");
        assertThat(columns).extracting(column -> column.get("value_kind"))
                .containsExactly("text", null);
        assertThat(result.packFields()).extracting(PackField::key)
                .contains("grantor")
                .doesNotContain("line_items");
    }

    @Test
    void dropsNestedDuplicatesWhenTableAlreadyMined() {
        PackMiningClient.MiningResult mined = new PackMiningClient.MiningResult(
                "demo.schedule",
                "legal",
                List.of("Site Schedule"),
                List.of(
                        field("grantor", "identity > Legal Name", "identity", "text",
                                List.of("Grantor")),
                        field("line_items.description",
                                "economic > Project Cost > Line Item > Description",
                                "economic", "text", List.of("Description")),
                        field("line_items.amount",
                                "economic > Project Cost > Line Item > Amount",
                                "economic", "quantity", List.of("Amount"))),
                List.of(new PackMiningClient.MinedTable(
                        "line_items",
                        "economic > Project Cost > Line Item",
                        "economic",
                        "description",
                        null,
                        null,
                        null,
                        List.of(
                                new PackMiningClient.MinedColumn("description", "text"),
                                new PackMiningClient.MinedColumn("amount", "money")),
                        List.of("d1"),
                        true)),
                List.of(),
                List.of(),
                null,
                null,
                null);

        Generalizer.Result result = generalizer.generalize(
                mined, 1, Generalizer.Config.defaults(Set.of()));

        assertThat(result.tables()).hasSize(1);
        Map<String, Object> table = result.tables().getFirst();
        assertThat(table.get("name")).isEqualTo("line_items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("fields");
        assertThat(columns).extracting(column -> column.get("value_kind"))
                .containsExactly("text", "money");
        assertThat(result.packFields()).extracting(PackField::key)
                .containsExactly("grantor")
                .doesNotContain("line_items.description", "line_items.amount");
    }

    @Test
    void oneColumnTextNestedRowBecomesTable() {
        PackMiningClient.MiningResult mined = new PackMiningClient.MiningResult(
                "demo.schedule",
                "legal",
                List.of("Site Schedule"),
                List.of(
                        field("grantor", "identity > Legal Name", "identity", "text",
                                List.of("Grantor")),
                        field("line_items", "economic > Project Cost > Line Item", "economic",
                                "list", List.of("Line Items")),
                        field("line_items.description",
                                "economic > Project Cost > Line Item > Description",
                                "economic", "text", List.of("Description"))));

        Generalizer.Result result = generalizer.generalize(
                mined, 1, Generalizer.Config.defaults(Set.of()));

        assertThat(result.tables()).hasSize(1);
        Map<String, Object> table = result.tables().getFirst();
        assertThat(table.get("name")).isEqualTo("line_items");
        assertThat(table.get("key_column")).isEqualTo("description");
        assertThat(result.packFields()).extracting(PackField::key)
                .containsExactly("grantor")
                .doesNotContain("line_items.description");
    }

    @Test
    void oneColumnQuantityNestedRowStaysUnrewritten() {
        PackMiningClient.MiningResult mined = new PackMiningClient.MiningResult(
                "demo.schedule",
                "legal",
                List.of("Site Schedule"),
                List.of(
                        field("grantor", "identity > Legal Name", "identity", "text",
                                List.of("Grantor")),
                        field("line_items", "economic > Project Cost > Line Item", "economic",
                                "list", List.of("Line Items")),
                        field("line_items.amount",
                                "economic > Project Cost > Line Item > Amount",
                                "economic", "quantity", List.of("Amount"))));

        Generalizer.Result result = generalizer.generalize(
                mined, 1, Generalizer.Config.defaults(Set.of()));

        assertThat(result.tables()).isEmpty();
        assertThat(result.packFields()).extracting(PackField::key)
                .contains("grantor", "line_items.amount");

        PackQualityGates.GateReport report = PackQualityGates.evaluate(
                packOf(result),
                new PackQualityGates.CoverageProbe(Map.of("d1", Set.of("grantor"))),
                0.6,
                Set.of(),
                List.of("d1"));
        assertThat(report.gate("nested_schedule_shape").value().passed()).isFalse();
        assertThat(report.rejectReasons()).anyMatch(reason ->
                reason.contains("line_items.amount") && reason.contains("nested"));
    }

    private static PackMiningClient.MiningResult nestedLineItems() {
        return new PackMiningClient.MiningResult(
                "demo.schedule",
                "legal",
                List.of("Site Schedule"),
                List.of(
                        field("grantor", "identity > Legal Name", "identity", "text",
                                List.of("Grantor")),
                        field("line_items", "economic > Project Cost > Line Item", "economic",
                                "list", List.of("Line Items")),
                        field("line_items.description",
                                "economic > Project Cost > Line Item > Description",
                                "economic", "text", List.of("Description")),
                        field("line_items.amount",
                                "economic > Project Cost > Line Item > Amount",
                                "economic", "quantity", List.of("Amount"))));
    }

    private static DocTypePack packOf(Generalizer.Result result) {
        return new DocTypePack(
                "demo.schedule",
                "1.0.0",
                "legal",
                "land_document",
                List.of("general"),
                result.identitySignals(),
                result.packFields(),
                result.tables(),
                List.of(),
                List.of());
    }

    private static PackMiningClient.MinedField field(
            String key, String path, String pathRoot, String valueKind, List<String> labels) {
        return new PackMiningClient.MinedField(
                key, path, pathRoot, valueKind, true, null, List.of(), labels,
                List.of("d1"), List.of(), true);
    }
}
