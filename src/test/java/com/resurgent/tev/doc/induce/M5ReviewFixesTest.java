package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.llm.DocLlmEnvironment;
import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.residency.DataResidency;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Review fixes for mandate residency, a single evidenced document, and the
 * closed induction reason codes.
 */
class M5ReviewFixesTest {

    @Test
    void unsetResidencyStillSearchesWhenAUrlIsConfigured() throws Exception {
        List<String> queries = new ArrayList<>();
        WebSearchClient web = query -> {
            queries.add(query);
            return "domain structure";
        };
        PackMiningClient mining = PackMiningClients.select(
                Map.of("TEV_WEB_SEARCH_URL", "https://example.test/search"),
                new FakeDocLlmClient("{\"fields\":[]}"),
                web,
                null);

        mining.mine(request("Rated Facility"));

        assertThat(queries).hasSize(1);
        assertThat(mining.id()).startsWith("llm-mining");
    }

    @Test
    void mandateLocalOnlyBlocksWebSearchEvenWhenTheProcessAllowsCloud() throws Exception {
        List<String> queries = new ArrayList<>();
        WebSearchClient web = query -> {
            queries.add(query);
            return "should not be fetched";
        };
        FakeDocLlmClient llm = new FakeDocLlmClient("{\"fields\":[]}");
        PackMiningClient mining = PackMiningClients.select(
                Map.of(
                        "TEV_DATA_RESIDENCY", "cloud_allowed",
                        "TEV_WEB_SEARCH_URL", "https://example.test/search"),
                llm,
                web,
                null,
                DataResidency.LOCAL_ONLY);

        mining.mine(request("Rated Facility"));

        assertThat(queries).isEmpty();
        assertThat(llm.calls()).isZero();
    }

    @Test
    void processFloorOverridesACloudAllowedMandateRow() throws Exception {
        List<String> queries = new ArrayList<>();
        WebSearchClient web = query -> {
            queries.add(query);
            return "should not be fetched";
        };
        FakeDocLlmClient llm = new FakeDocLlmClient("{\"fields\":[]}");
        PackMiningClient mining = PackMiningClients.select(
                Map.of(
                        "TEV_DATA_RESIDENCY", "local_only",
                        "TEV_WEB_SEARCH_URL", "https://example.test/search"),
                llm,
                web,
                null,
                DataResidency.CLOUD_ALLOWED);

        mining.mine(request("Rated Facility"));

        assertThat(queries).isEmpty();
        assertThat(llm.calls()).isZero();
    }

    @Test
    void lockedWebClientDoesNotSearch() {
        int[] calls = {0};
        HttpWebSearchClient client = new HttpWebSearchClient(
                "http://example.test/search",
                DataResidency.LOCAL_ONLY,
                query -> {
                    calls[0]++;
                    return "snippet";
                });

        assertThat(client.search("credit rating report fields")).isEmpty();
        assertThat(calls).containsExactly(0);

        HttpWebSearchClient open = new HttpWebSearchClient(
                "http://example.test/search",
                DataResidency.CLOUD_ALLOWED,
                query -> "rating outlook facilities");
        assertThat(open.search("credit rating")).isEqualTo("rating outlook facilities");
    }

    @Test
    void minerReadsLaterPageStructureAndDoesNotCapTheSchema() throws Exception {
        StringBuilder late = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            late.append("Heading number ").append(i).append(" of the schedule\n");
        }
        late.append("Final page covenant heading\n");
        late.append("Facility | Borrower | Site | Tenor | Pricing\n");
        FakeDocLlmClient llm = new FakeDocLlmClient("{\"fields\":[]}");
        List<String> queries = new ArrayList<>();
        LlmPackMiningClient mining = new LlmPackMiningClient(llm, null, query -> {
            queries.add(query);
            return "";
        }, Map.of(
                "TEV_DATA_RESIDENCY", "cloud_allowed",
                "TEV_WEB_SEARCH_URL", "https://example.test/search"));

        mining.mine(new PackMiningClient.MiningRequest(
                "debt_syndication",
                "credit_report",
                "report.credit_rating",
                List.of(new PackMiningClient.MiningDocument(
                        "d1",
                        "credit_report",
                        "Credit Rating Report",
                        List.of(late.toString()),
                        Set.of()))));

        assertThat(llm.lastUser()).contains("Final page covenant heading");
        assertThat(llm.lastUser()).contains("Facility | Borrower | Site | Tenor | Pricing");
        assertThat(llm.lastSystem()).doesNotContain("at most 12");
        assertThat(llm.lastSystem().toLowerCase()).doesNotContain("across multiple documents");
        assertThat(llm.lastSystem().toLowerCase()).contains("single");
        assertThat(queries.get(0)).contains("final page covenant heading");
    }

    @Test
    void singleDocumentDomainStandardUnderASpineRootIsKept() {
        PackMiningClient.MiningResult mined = new PackMiningClient.MiningResult(
                "report.credit_rating",
                "report",
                List.of("Credit Rating Report"),
                List.of(new PackMiningClient.MinedField(
                        "agency_scale",
                        "regulatory > Agency Scale",
                        "regulatory",
                        "text",
                        true,
                        "agency rating scale",
                        List.of(),
                        List.of("Agency Scale"),
                        List.of(),
                        List.of(),
                        true)));

        Generalizer.Result result = new Generalizer().generalize(
                mined, 1, Generalizer.Config.defaults(Set.of()));

        assertThat(result.packFields()).extracting(field -> field.key()).contains("agency_scale");
        assertThat(result.dropped()).noneMatch(line -> line.contains("agency_scale"));
        assertThat(result.decisions()).anySatisfy(note -> {
            assertThat(note.candidateKey()).isEqualTo("agency_scale");
            assertThat(note.reasonCode()).isIn(
                    "web_domain_standard", "single_doc_validated", "spine_match");
        });
    }

    @Test
    void reasonCodesUseTheClosedSpecSet() {
        assertThat(InductionReasonCodes.canonical("entity_token")).isEqualTo("entity_token_rejected");
        assertThat(InductionReasonCodes.canonical("label_not_value")).isEqualTo("entity_token_stripped");
        assertThat(InductionReasonCodes.canonical("below_consensus")).isEqualTo("below_threshold");
        assertThat(InductionReasonCodes.canonical("not_evidenced")).isEqualTo("below_threshold");
        assertThat(InductionReasonCodes.canonical("single_document_industry_leaf"))
                .isEqualTo("single_doc_validated");
        assertThat(InductionReasonCodes.canonical("widen_table")).isEqualTo("cluster_promoted");
        assertThat(InductionReasonCodes.canonical("spine_leaf")).isEqualTo("spine_match");
        assertThat(InductionReasonCodes.canonical("domain_standard")).isEqualTo("web_domain_standard");
        assertThat(InductionReasonCodes.canonical("web_domain_standard")).isEqualTo("web_domain_standard");
        assertThat(InductionReasonCodes.SPEC).containsExactlyInAnyOrder(
                "web_domain_standard",
                "spine_match",
                "single_doc_validated",
                "entity_token_stripped",
                "entity_token_rejected",
                "low_coverage",
                "cluster_promoted",
                "below_threshold");
    }

    @Test
    void mandateOrProcessFloorBlocksTheDocumentLlm() {
        Map<String, String> open = Map.of(
                "OPENROUTER_API_KEY", "test-key",
                "DOC_LLM_MODEL", "test-model");
        assertThat(DocLlmEnvironment.clientOrNull(open, null)).isNotNull();
        assertThat(DocLlmEnvironment.clientOrNull(open, DataResidency.LOCAL_ONLY)).isNull();
        Map<String, String> floor = Map.of(
                "OPENROUTER_API_KEY", "test-key",
                "DOC_LLM_MODEL", "test-model",
                "TEV_DATA_RESIDENCY", "local_only");
        assertThat(DocLlmEnvironment.clientOrNull(floor, DataResidency.CLOUD_ALLOWED)).isNull();
    }

    @Test
    void netWorthSeedPackUsesTheRequestedCodeAndKeepsTheOldCode() throws Exception {
        DocTypePack shipped = PackLoader.findSeed("identity.net_worth_certificate");
        DocTypePack legacy = PackLoader.findSeed("identity.promoter_net_worth");

        assertThat(shipped.code()).isEqualTo("identity.net_worth_certificate");
        assertThat(legacy.code()).isEqualTo("identity.net_worth_certificate");
        assertThat(legacy.fields()).extracting(field -> field.key())
                .contains("net_worth", "assets_total");
        assertThat(PackLoader.loadSeedPacks()).extracting(DocTypePack::code)
                .contains("identity.net_worth_certificate")
                .doesNotContain("identity.promoter_net_worth");
    }

    private static PackMiningClient.MiningRequest request(String line) {
        return new PackMiningClient.MiningRequest(
                "debt_syndication",
                "credit_report",
                "report.credit_rating",
                List.of(new PackMiningClient.MiningDocument(
                        "d1", "credit_report", "Credit Rating Report", List.of(line), Set.of())));
    }
}
