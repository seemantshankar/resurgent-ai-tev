package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
/**
 * Live pack mining (S2.11) against a fake {@link com.resurgent.tev.doc.llm.DocLlmClient}:
 * cross-document consensus is attributed deterministically from printed labels, and the
 * {@link Generalizer} strips company names / one-off fields before the gates run.
 */
class LlmPackMiningClientTest {

    @TempDir
    Path tempDir;

    private static final String D1 = """
            GST Certificate
            GSTIN: 36AAOCB3072G1ZM
            Legal Name: Bhagyanagar Solar Private Limited
            Bhagyanagar Solar Net Worth: Rs. 1,00,00,000
            Offer Date: 12/03/2026""";

    private static final String D2 = """
            GST Certificate
            GSTIN: 23ACUPM9971C1Z5
            Legal Name: Bhagyanagar Solar Private Limited
            Bhagyanagar Solar Net Worth: Rs. 2,00,00,000""";

    private static final String MINING_JSON = """
            {
              "suggested_code": "identity.kyc",
              "family": "identity",
              "identity_signals": ["GST Certificate"],
              "fields": [
                {"key":"gstin","path":"identity > GSTIN","path_root":"identity",
                 "value_kind":"text","required":true,"prompt_hint":"GSTIN printed on the certificate",
                 "labels":["GSTIN","GST Number"],"sample_values":["36AAOCB3072G1ZM"]},
                {"key":"legal_name","path":"identity > Legal Name","path_root":"identity",
                 "value_kind":"text","required":true,"prompt_hint":"registered legal name",
                 "labels":["Legal Name"],"sample_values":["Bhagyanagar Solar Private Limited"]},
                {"key":"bhagyanagar_solar_net_worth",
                 "path":"economic > Promoter Net Worth > Net Worth","path_root":"economic",
                 "value_kind":"money","required":true,"prompt_hint":"net worth",
                 "labels":["Bhagyanagar Solar Net Worth"],"sample_values":["Rs. 1,00,00,000"]},
                {"key":"offer_date","path":"frame > Document Title > Date","path_root":"frame",
                 "value_kind":"date","required":false,"prompt_hint":"offer date",
                 "labels":["Offer Date"],"sample_values":["12/03/2026"]}
              ]
            }""";

    /**
     * Recorded mining JSON for a schedule-like cluster: table row objects with
     * description / amount / period / unit columns and value_kind on every leaf.
     * Synthetic labels only — no client names or amounts.
     */
    private static final String SCHEDULE_JSON = """
            {
              "suggested_code": "economic.line_schedule",
              "family": "economic",
              "identity_signals": ["Line Schedule"],
              "fields": [
                {"key":"statement_date","path":"frame > Document Title > Date","path_root":"frame",
                 "value_kind":"date","required":true,"prompt_hint":"statement date",
                 "labels":["Statement Date"],"sample_values":["<date>"]}
              ],
              "tables": [
                {"name":"line_schedule","path":"economic > Line Item","path_root":"economic",
                 "key_column":"description","period_key":"period","domain_standard":true,
                 "columns":[
                   {"key":"description","value_kind":"text"},
                   {"key":"amount","value_kind":"money"},
                   {"key":"period","value_kind":"date"},
                   {"key":"unit","value_kind":"text"}
                 ]}
              ]
            }""";

    private static PackMiningClient.MiningRequest request() {
        return new PackMiningClient.MiningRequest("solar", "identity", "identity.kyc",
                List.of(
                        new PackMiningClient.MiningDocument("d1", "identity", "GST Certificate",
                                List.of(D1), Set.of("Bhagyanagar Solar")),
                        new PackMiningClient.MiningDocument("d2", "identity", "GST Certificate",
                                List.of(D2), Set.of("Bhagyanagar Solar"))));
    }

    @Test
    void attributesEachFieldToTheDocumentsThatPrintItsLabel() throws Exception {
        FakeDocLlmClient llm = new FakeDocLlmClient(MINING_JSON);
        LlmPackMiningClient mining = new LlmPackMiningClient(llm, null);

        PackMiningClient.MiningResult result = mining.mine(request());

        assertThat(result.suggestedCode()).isEqualTo("identity.kyc");
        assertThat(result.identitySignals()).contains("GST Certificate");
        PackMiningClient.MinedField gstin = field(result, "gstin");
        assertThat(gstin.docIds()).containsExactly("d1", "d2");
        PackMiningClient.MinedField offer = field(result, "offer_date");
        assertThat(offer.docIds()).containsExactly("d1");
        // the prompt carries printed labels/layout, never a full document body
        assertThat(llm.lastUser()).contains("GSTIN").contains("Legal Name");
        assertThat(llm.lastSystem()).contains("identity > PAN");
    }

    @Test
    void systemPromptRequiresTableShapedSchedulesAndValueKindOnEveryLeaf() throws Exception {
        FakeDocLlmClient llm = new FakeDocLlmClient(MINING_JSON);
        new LlmPackMiningClient(llm, null).mine(request());

        String system = llm.lastSystem();
        assertThat(system).contains("value_kind");
        assertThat(system.toLowerCase()).contains("every field");
        assertThat(system.toLowerCase()).contains("every table column");
        assertThat(system.toLowerCase()).contains("row objects");
        assertThat(system).contains("tables[]");
        assertThat(system.toLowerCase()).contains("description");
        assertThat(system.toLowerCase()).contains("unit");
        assertThat(system).contains("\"name\":\"snake_name\"");
        assertThat(system).contains("\"key\":\"description\"");
        assertThat(system).contains("\"key\":\"amount\"");
        assertThat(system).contains("\"key\":\"period\"");
        assertThat(system).contains("\"key\":\"unit\"");
        assertThat(system.toLowerCase()).doesNotContain("tax audit");
        assertThat(system.toLowerCase()).doesNotContain("pvsyst");
    }

    @Test
    void parsesScheduleTableWithValueKindOnEveryColumn() throws Exception {
        FakeDocLlmClient llm = new FakeDocLlmClient(SCHEDULE_JSON);
        PackMiningClient.MiningResult result = new LlmPackMiningClient(llm, null).mine(request());

        PackMiningClient.MinedTable table = result.tables().stream()
                .filter(t -> "line_schedule".equals(t.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing mined table line_schedule"));
        assertThat(table.keyColumn()).isEqualTo("description");
        assertThat(table.periodKey()).isEqualTo("period");
        assertThat(table.columns()).extracting(PackMiningClient.MinedColumn::key)
                .containsExactly("description", "amount", "period", "unit");
        assertThat(table.columns()).extracting(PackMiningClient.MinedColumn::valueKind)
                .containsExactly("text", "money", "date", "text");
        assertThat(field(result, "statement_date").valueKind()).isEqualTo("date");
    }

    @Test
    void nestedNumberWithoutValueKindStaysUndeclared() throws Exception {
        PackMiningClient.MiningResult result = LlmPackMiningClient.interpret("""
                {
                  "suggested_code": "demo.schedule",
                  "family": "legal",
                  "identity_signals": ["Site Schedule"],
                  "fields": [
                    {"key":"line_items","path":"economic > Line Item","path_root":"economic",
                     "value_kind":"list","required":true,"labels":["Line Items"],
                     "items":{"type":"object","properties":{
                       "description":{"value_kind":"text"},
                       "amount":{"type":"number"}
                     }}}
                  ]
                }
                """, request());
        PackMiningClient.MinedField amount = field(result, "line_items.amount");
        assertThat(amount.valueKind()).isNull();
        assertThat(field(result, "line_items.description").valueKind()).isEqualTo("text");
    }

    @Test
    void generalizerStripsEntityNamesAndOneOffFieldsBeforePublishing() throws Exception {
        PackStore store = new PackStore(tempDir.resolve("packs"));
        FakeDocLlmClient llm = new FakeDocLlmClient(MINING_JSON);
        LlmPackMiningClient mining = new LlmPackMiningClient(llm, null);

        PackInducer.InduceResult result = new PackInducer().induce(new PackInducer.InduceRequest(
                List.of(
                        new PackInducer.InduceDocument("d1", "identity", "GST Certificate",
                                List.of(D1), Set.of("Bhagyanagar Solar")),
                        new PackInducer.InduceDocument("d2", "identity", "GST Certificate",
                                List.of(D2), Set.of("Bhagyanagar Solar"))),
                "solar", "identity", "identity.kyc", "identity",
                mining, store, 0.6, 2, false));

        assertThat(result.published()).isTrue();
        assertThat(result.pack().fields()).extracting(PackField::key)
                .contains("gstin", "legal_name", "net_worth");
        assertThat(result.pack().fields()).extracting(PackField::key)
                .noneMatch(k -> k.toLowerCase().contains("bhagyanagar"));
        assertThat(result.droppedFields())
                .anyMatch(d -> d.contains("offer_date") && d.contains("one-off"));
        assertThat(result.rewrittenFields())
                .anyMatch(r -> r.toLowerCase().contains("bhagyanagar"));
    }

    private static PackMiningClient.MinedField field(PackMiningClient.MiningResult result,
            String key) {
        return result.fields().stream()
                .filter(f -> key.equals(f.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing mined field " + key));
    }
}
