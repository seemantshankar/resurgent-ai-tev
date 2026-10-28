package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackLoader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Induced packs must not publish when promoted leaves lack {@code value_kind}
 * or the pack fails extract-schema compile (#199).
 */
class PackQualityGatesTest {

    private static final String VALID_FIXTURE = """
            code: quotation.vendor_supply
            version: 0.0.0
            family: quotation
            file_role: quotation
            identity_signals:
              any_of: ["Quotation"]
            fields:
              - key: vendor_name
                path: identity > Legal Name
                path_root: identity
                value_kind: text
                required: true
              - key: gross_total
                path: economic > Project Cost > Quoted Gross
                path_root: economic
                value_kind: money
                required: true
            tables:
              - name: line_items
                path: economic > Project Cost > Line Item
                path_root: economic
                key_column: description
                fields:
                  - key: description
                    value_kind: text
                  - key: amount
                    value_kind: money
            """;

    @Test
    void missingTableColumnValueKindFailsWithTableColumnMessage() {
        DocTypePack pack = packWithColumnKinds("text", null);

        PackQualityGates.GateReport report = evaluate(pack);

        assertThat(report.allPassed()).isFalse();
        assertThat(report.rejectReasons()).anyMatch(reason ->
                reason.contains("line_items.amount") && reason.contains("value_kind"));
    }

    @Test
    void missingKeyColumnValueKindFailsWithTableColumnMessage() {
        DocTypePack pack = packWithColumnKinds(null, "money");

        PackQualityGates.GateReport report = evaluate(pack);

        assertThat(report.allPassed()).isFalse();
        assertThat(report.rejectReasons()).anyMatch(reason ->
                reason.contains("line_items.description") && reason.contains("value_kind"));
    }

    @Test
    void blankTableColumnValueKindFailsWithTableColumnMessage() {
        DocTypePack pack = packWithColumnKinds("text", "  ");

        PackQualityGates.GateReport report = evaluate(pack);

        assertThat(report.allPassed()).isFalse();
        assertThat(report.rejectReasons()).anyMatch(reason ->
                reason.contains("line_items.amount") && reason.contains("value_kind"));
    }

    @Test
    void missingFieldValueKindFailsWithFieldKeyMessage() {
        DocTypePack pack = new DocTypePack(
                "quotation.vendor_supply",
                "0.0.0",
                "quotation",
                "quotation",
                List.of("general"),
                List.of("Quotation"),
                List.of(
                        new PackField(
                                "vendor_name",
                                "identity > Legal Name",
                                "identity",
                                null,
                                true,
                                List.of(),
                                null,
                                List.of()),
                        PackField.required(
                                "gross_total",
                                "economic > Project Cost > Quoted Gross",
                                "economic",
                                ValueKind.MONEY)),
                List.of(),
                List.of(),
                List.of());

        PackQualityGates.GateReport report = evaluate(pack);

        assertThat(report.allPassed()).isFalse();
        assertThat(report.rejectReasons()).anyMatch(reason ->
                reason.contains("vendor_name") && reason.contains("value_kind"));
    }

    @Test
    void validFixturePackPassesCompileAndKindGates() throws Exception {
        DocTypePack pack = loadYaml(VALID_FIXTURE);

        PackQualityGates.GateReport report = evaluate(pack);

        assertThat(report.gate("pack_schema_compiles").value().passed()).isTrue();
        assertThat(report.gate("required_fields_valid").value().passed()).isTrue();
        assertThat(report.allPassed()).isTrue();
    }

    @Test
    void leftoverDottedNestedFieldFailsShapeGate() {
        DocTypePack pack = new DocTypePack(
                "quotation.vendor_supply",
                "0.0.0",
                "quotation",
                "quotation",
                List.of("general"),
                List.of("Quotation"),
                List.of(
                        PackField.required(
                                "vendor_name",
                                "identity > Legal Name",
                                "identity",
                                ValueKind.TEXT),
                        PackField.required(
                                "line_items.amount",
                                "economic > Project Cost > Line Item > Amount",
                                "economic",
                                ValueKind.QUANTITY)),
                List.of(),
                List.of(),
                List.of());

        PackQualityGates.GateReport report = evaluate(pack);

        assertThat(report.gate("nested_schedule_shape").value().passed()).isFalse();
        assertThat(report.allPassed()).isFalse();
        assertThat(report.rejectReasons()).anyMatch(reason ->
                reason.contains("line_items.amount") && reason.contains("nested"));
    }

    @Test
    void packThatDoesNotCompileFailsCompileGate() {
        DocTypePack pack = packWithColumnKinds("text", null);

        PackQualityGates.GateReport report = evaluate(pack);

        assertThat(report.gate("pack_schema_compiles").value().passed()).isFalse();
        assertThat(report.gate("pack_schema_compiles").value().message())
                .contains("line_items.amount")
                .contains("value_kind");
    }

    private static PackQualityGates.GateReport evaluate(DocTypePack pack) {
        return PackQualityGates.evaluate(pack, coverage(pack), 0.6, Set.of(), List.of("d1"));
    }

    private static DocTypePack packWithColumnKinds(String descriptionKind, String amountKind) {
        Map<String, Object> description = column("description", descriptionKind);
        Map<String, Object> amount = column("amount", amountKind);
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("name", "line_items");
        table.put("path", "economic > Project Cost > Line Item");
        table.put("path_root", "economic");
        table.put("key_column", "description");
        table.put("fields", List.of(description, amount));
        return new DocTypePack(
                "quotation.vendor_supply",
                "0.0.0",
                "quotation",
                "quotation",
                List.of("general"),
                List.of("Quotation"),
                List.of(
                        PackField.required("vendor_name", "identity > Legal Name", "identity", ValueKind.TEXT),
                        PackField.required(
                                "gross_total",
                                "economic > Project Cost > Quoted Gross",
                                "economic",
                                ValueKind.MONEY)),
                List.of(table),
                List.of(),
                List.of());
    }

    private static Map<String, Object> column(String key, String valueKind) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("key", key);
        if (valueKind != null) {
            column.put("value_kind", valueKind);
        }
        return column;
    }

    private static PackQualityGates.CoverageProbe coverage(DocTypePack pack) {
        Set<String> keys = Set.copyOf(pack.requiredFields().stream().map(PackField::key).toList());
        return new PackQualityGates.CoverageProbe(Map.of("d1", keys));
    }

    private static DocTypePack loadYaml(String yaml) throws Exception {
        return PackLoader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "fixture-pack");
    }
}
