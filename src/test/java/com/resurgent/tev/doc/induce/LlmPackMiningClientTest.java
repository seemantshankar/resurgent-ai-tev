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
