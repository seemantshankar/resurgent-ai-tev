package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaJson;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sanitized web-grounded induction (issue #176): a single document still yields a
 * full pack, the outbound search query carries no client identifiers, and the
 * decision log is visible from mandate QA.
 */
class SanitizedPackInductionTest {

    @TempDir
    Path tempDir;

    @Test
    void spineCatalogAddsDebtSyndicationLeavesUnderTheSevenRoots() {
        assertThat(DocTypePack.SPINE_ROOTS).containsExactly(
                "identity", "economic", "technical", "commercial", "legal", "regulatory", "frame");
        assertThat(SpineCatalog.paths()).contains(
                "economic > Credit Rating",
                "economic > Rating Outlook",
                "economic > Rated Facility",
                "economic > Facility Tranche",
                "economic > Facility Pricing",
                "economic > Facility Tenor",
                "economic > Facility Moratorium",
                "economic > Facility Repayment",
                "legal > Facility Security",
                "legal > Facility Covenant",
                "regulatory > CIBIL Score",
                "regulatory > CMR Rank",
                "regulatory > Days Past Due",
                "regulatory > SMA Status",
                "regulatory > Wilful Defaulter",
                "economic > Average Bank Balance",
                "economic > Cheque Bounce",
                "economic > Limit Utilization",
                "identity > Promoter Qualification",
                "identity > Promoter Experience",
                "identity > Management Qualification",
                "identity > Management Experience");
        assertThat(SpineCatalog.leaves()).allSatisfy(leaf ->
                assertThat(DocTypePack.SPINE_ROOTS).contains(leaf.pathRoot()));
        assertThat(SpineCatalog.matchLabel("tenor").orElseThrow().path())
                .isEqualTo("commercial > Offtake > Tenor");
        assertThat(SpineCatalog.matchLabel("facility tenor").orElseThrow().path())
                .isEqualTo("economic > Facility Tenor");
        assertThat(SpineCatalog.matchLabel("willful defaulter").orElseThrow().path())
                .isEqualTo("regulatory > Wilful Defaulter");
    }

    @Test
    void genericTitlesAndFilenamesAreNotSampleEntityTokens() {
        assertThat(EntityTokenFilter.sampleTokens(
                "Credit Rating Report", "Bank Statement.pdf", List.of("CIBIL Report")))
                .isEmpty();

        Set<String> named = EntityTokenFilter.sampleTokens(
                "Arham Ventures Credit Rating Report",
                "ABCDE1234F.pdf",
                List.of("Account No: 123456789012", "Rated amount ₹ 12,50,00,000"));
        assertThat(named).anyMatch(token -> token.toLowerCase().contains("arham"));
        assertThat(named).anyMatch(token -> token.contains("ABCDE1234F"));
        assertThat(named).anyMatch(token -> token.contains("123456789012")
                || token.contains("12,50,00,000"));
        assertThat(named).noneMatch(token -> token.equalsIgnoreCase("Credit Rating Report")
                || token.equalsIgnoreCase("CIBIL Report")
                || token.equalsIgnoreCase("Bank Statement.pdf"));
    }

    @Test
    void outboundWebQueryUsesTheFullDocumentAndCarriesNoClientIdentifiers() throws Exception {
        List<String> queries = new ArrayList<>();
        WebSearchClient web = query -> {
            queries.add(query);
            return "A credit rating report states rating, outlook, rated facilities, and covenants.";
        };
        FakeDocLlmClient llm = new FakeDocLlmClient(CREDIT_RATING_JSON);
        LlmPackMiningClient mining = new LlmPackMiningClient(llm, null, web, Map.of(
                "TEV_DATA_RESIDENCY", "cloud_allowed",
                "TEV_WEB_SEARCH_URL", "https://example.test/search"));

        PackMiningClient.MiningResult mined = mining.mine(creditRequest());

        assertThat(queries).hasSize(1);
        String query = queries.get(0).toLowerCase();
        assertThat(query).contains("rated facility");
        assertNoClientIdentifiers(query);
        assertThat(llm.lastUser().toLowerCase()).contains("rated facility");
        assertNoClientIdentifiers(llm.lastUser().toLowerCase());
        assertThat(mined.webSearchQuery()).isEqualTo(queries.get(0));
        assertThat(mined.tables()).isNotEmpty();
        assertThat(mined.clauses()).isNotEmpty();
        assertThat(mined.passages()).isNotEmpty();
        assertThat(mined.documentDateKind()).isEqualTo("issue");
    }

    @Test
    void localOnlyMinesOfflineAndDoesNotCallWebOrTheCloudModel() throws Exception {
        List<String> queries = new ArrayList<>();
        WebSearchClient web = query -> {
            queries.add(query);
            return "should not be fetched";
        };
        FakeDocLlmClient llm = new FakeDocLlmClient(CREDIT_RATING_JSON);
        PackMiningClient mining = PackMiningClients.select(
                Map.of("TEV_DATA_RESIDENCY", "local_only",
                        "TEV_WEB_SEARCH_URL", "https://example.test/search"),
                llm, web, null);

        PackMiningClient.MiningResult mined = mining.mine(creditRequest());

        assertThat(queries).isEmpty();
        assertThat(llm.calls()).isZero();
        assertThat(mined.tables()).extracting(table -> table.name()).contains("rated_facilities");
        assertThat(mined.clauses()).isNotEmpty();
        assertThat(mined.passages()).isNotEmpty();
        assertThat(mined.documentDateKind()).isEqualTo("issue");
        assertNoClientIdentifiers(mined.webSearchQuery().toLowerCase());
    }

    @Test
    void singleDocumentInductionPublishesFieldsTablesClausesAndPassages() throws Exception {
        List<String> queries = new ArrayList<>();
        WebSearchClient web = query -> {
            queries.add(query);
            return "Analysts extract rating, outlook, a rated facility schedule, and covenants.";
        };
        PackStore store = new PackStore(tempDir.resolve("packs"));
        PackInducer.InduceResult result = new PackInducer().induce(new PackInducer.InduceRequest(
                List.of(creditDocument("d1")),
                "debt_syndication",
                "credit_report",
                "report.credit_rating",
                "report",
                new LlmPackMiningClient(new FakeDocLlmClient(CREDIT_RATING_JSON), null, web, Map.of(
                        "TEV_DATA_RESIDENCY", "cloud_allowed",
                        "TEV_WEB_SEARCH_URL", "https://example.test/search")),
                store,
                0.6,
                1,
                false));

        assertThat(result.published()).isTrue();
        DocTypePack pack = result.pack();
        assertThat(pack.fields()).extracting(PackField::key)
                .contains("credit_rating", "rating_outlook", "agency_scale");
        assertThat(field(pack, "credit_rating").required()).isTrue();
        assertThat(field(pack, "credit_rating").path()).isEqualTo("economic > Credit Rating");
        assertThat(field(pack, "agency_scale").required()).isFalse();
        assertThat(field(pack, "agency_scale").path()).startsWith("regulatory >");

        Map<String, Object> facilities = table(pack, "rated_facilities");
        assertThat(facilities.get("key_column")).isEqualTo("facility");
        assertThat(facilities.get("entity_key")).isEqualTo("borrower");
        assertThat(facilities.get("site_key")).isEqualTo("site");
        Map<String, Object> trend = table(pack, "utilization_trend");
        assertThat(trend.get("key_column")).isEqualTo("metric");
        assertThat(trend.get("period_key")).isEqualTo("month");

        assertThat(pack.clauseTypes()).extracting(type -> type.key()).contains("financial_covenant");
        assertThat(pack.passages()).extracting(passage -> passage.topic()).contains("rating_rationale");
        assertThat(pack.documentDateKind()).isEqualTo("issue");
        assertThat(queries).hasSize(1);
        assertNoClientIdentifiers(queries.get(0).toLowerCase());
        assertThat(result.decisions()).anySatisfy(note -> {
            assertThat(note.outcome()).isIn("promoted", "demoted_optional", "rewritten");
            assertThat(note.detailJson()).contains(queries.get(0));
            assertNoClientIdentifiers(note.detailJson().toLowerCase());
        });
        assertThat(result.decisions()).anySatisfy(note -> {
            assertThat(note.candidateKey()).isEqualTo("agency_scale");
            assertThat(note.outcome()).isEqualTo("demoted_optional");
        });

        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("workspace.db"))) {
            InductionDecisionLog.recordPack(
                    db.connection(), 10L, null, pack.code(), result.decisions());
            MandateQa qa = new AssessmentRepository(db.connection()).readMandateQa(10L);
            assertThat(qa.inductionDecisions()).isNotEmpty();
            assertThat(qa.inductionDecisions()).anySatisfy(row -> {
                assertThat(row.stage()).isEqualTo("induce_pack");
                assertThat(row.outcome()).isEqualTo("demoted_optional");
                assertThat(row.candidateKey()).isEqualTo("agency_scale");
                assertThat(row.detailJson()).contains("sanitized_query");
                assertNoClientIdentifiers(row.detailJson().toLowerCase());
            });
            assertThat(MandateQaJson.write(qa)).contains("induction_decisions");
        }
    }

    @Test
    void reinductionWidensToTheNextVersionAndKeepsPriorPaths() throws Exception {
        PackStore store = new PackStore(tempDir.resolve("packs"));
        PackInducer.InduceResult first = new PackInducer().induce(request(store, mining(
                "report.scale",
                field("agency_scale", "regulatory > Agency Scale", "regulatory", true,
                        List.of("Scale", "Agency Scale")))));
        assertThat(first.published()).isTrue();
        assertThat(first.version()).isEqualTo("1.0.0");
        assertThat(field(first.pack(), "agency_scale").path()).isEqualTo("regulatory > Agency Scale");

        PackInducer.InduceResult second = new PackInducer().induce(request(store, mining(
                "report.scale",
                field("agency_scale", "regulatory > Other Scale", "regulatory", true,
                        List.of("Agency Scale", "Rating Scale")),
                field("rating_outlook", "economic > Rating Outlook", "economic", true,
                        List.of("Outlook", "Rating Outlook")),
                new PackMiningClient.MinedTable(
                        "rated_facilities",
                        "economic > Rated Facility",
                        "economic",
                        "facility",
                        null,
                        null,
                        null,
                        List.of(new PackMiningClient.MinedColumn("facility", "text"),
                                new PackMiningClient.MinedColumn("amount", "money")),
                        List.of("d1", "d2"),
                        true),
                new PackMiningClient.MinedClause(
                        "financial_covenant",
                        "Financial Covenant",
                        "covenant",
                        List.of("Financial Covenant"),
                        List.of("d1", "d2"),
                        true),
                new PackMiningClient.MinedPassage(
                        "rating_rationale",
                        List.of("Rating Rationale"),
                        List.of("agency_scale"),
                        List.of("d1", "d2"),
                        true),
                "issue")));

        assertThat(second.published()).isTrue();
        assertThat(second.version()).isEqualTo("1.1.0");
        DocTypePack widened = second.pack();
        assertThat(field(widened, "agency_scale").path()).isEqualTo("regulatory > Agency Scale");
        assertThat(field(widened, "agency_scale").required()).isTrue();
        assertThat(field(widened, "agency_scale").synonyms()).contains("Scale", "Rating Scale");
        assertThat(field(widened, "rating_outlook").required()).isFalse();
        assertThat(field(widened, "rating_outlook").path()).isEqualTo("economic > Rating Outlook");
        assertThat(table(widened, "rated_facilities").get("key_column")).isEqualTo("facility");
        assertThat(widened.clauseTypes()).extracting(type -> type.key()).contains("financial_covenant");
        assertThat(widened.passages()).extracting(passage -> passage.topic()).contains("rating_rationale");
        assertThat(widened.documentDateKind()).isEqualTo("issue");
        DocTypePack v1 = store.load("report.scale", "1.0.0").orElseThrow();
        assertThat(v1.fields()).extracting(PackField::key).containsExactly("agency_scale");
        assertThat(v1.tables()).isEmpty();
        assertThat(second.decisions()).anySatisfy(note -> {
            assertThat(note.candidateKey()).isEqualTo("rating_outlook");
            assertThat(note.outcome()).isEqualTo("demoted_optional");
        });
    }

    private static void assertNoClientIdentifiers(String text) {
        assertThat(text).doesNotContain("bhagyanagar");
        assertThat(text).doesNotContain("sharma");
        assertThat(text).doesNotContain("arham");
        assertThat(text).doesNotContain("abcde1234f");
        assertThat(text).doesNotContain("u40100tg2010ptc071234");
        assertThat(text).doesNotContain("36abcde1234f1z5");
        assertThat(text).doesNotContain("123456789012");
        assertThat(text).doesNotContain("12,50,00,000");
        assertThat(text).doesNotContain("₹");
    }

    private static PackField field(DocTypePack pack, String key) {
        return pack.fields().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    private static Map<String, Object> table(DocTypePack pack, String name) {
        return pack.tables().stream()
                .filter(t -> name.equals(t.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private static PackInducer.InduceDocument creditDocument(String id) {
        String filler = "x".repeat(1_800);
        return new PackInducer.InduceDocument(
                id,
                "credit_report",
                "Credit Rating Report",
                List.of(
                        "Credit Rating Report",
                        "Borrower: Bhagyanagar Solar Private Limited",
                        "Promoter: Ramesh Sharma",
                        "PAN: ABCDE1234F",
                        "CIN: U40100TG2010PTC071234",
                        "GSTIN: 36ABCDE1234F1Z5",
                        "Account No: 123456789012",
                        "Rated amount: ₹ 12,50,00,000",
                        filler,
                        "Rated Facility Schedule",
                        "Facility | Borrower | Site | Tenor | Pricing",
                        "Outlook: Stable",
                        "Agency Scale: AAA",
                        "Financial Covenant",
                        "Rating Rationale"),
                Set.of("Bhagyanagar Solar Private Limited", "Ramesh Sharma", "Arham Ventures"));
    }

    private static PackMiningClient.MiningRequest creditRequest() {
        PackInducer.InduceDocument doc = creditDocument("d1");
        return new PackMiningClient.MiningRequest(
                "debt_syndication", "credit_report", "report.credit_rating",
                List.of(doc.toMiningDocument()));
    }

    private static PackInducer.InduceRequest request(PackStore store, PackMiningClient mining) {
        return new PackInducer.InduceRequest(
                List.of(
                        new PackInducer.InduceDocument("d1", "credit_report", "Credit Rating Report",
                                List.of("Agency Scale: AAA", "Credit Rating Report"), Set.of()),
                        new PackInducer.InduceDocument("d2", "credit_report", "Credit Rating Report",
                                List.of("Agency Scale: AA", "Rating Outlook: Stable"), Set.of())),
                "debt_syndication",
                "credit_report",
                "report.scale",
                "report",
                mining,
                store,
                0.6,
                2,
                false);
    }

    private static PackMiningClient.MinedField field(
            String key, String path, String root, boolean required, List<String> labels) {
        return new PackMiningClient.MinedField(
                key, path, root, "text", required, key, List.of(), labels,
                List.of("d1", "d2"), List.of("sample"));
    }

    private static PackMiningClient mining(String code, PackMiningClient.MinedField... fields) {
        return new FixturePackMiningClient(new PackMiningClient.MiningResult(
                code, "report", List.of("Credit Rating Report"), List.of(fields)));
    }

    private static PackMiningClient mining(
            String code,
            PackMiningClient.MinedField first,
            PackMiningClient.MinedField second,
            PackMiningClient.MinedTable table,
            PackMiningClient.MinedClause clause,
            PackMiningClient.MinedPassage passage,
            String dateKind) {
        return new FixturePackMiningClient(new PackMiningClient.MiningResult(
                code,
                "report",
                List.of("Credit Rating Report"),
                List.of(first, second),
                List.of(table),
                List.of(clause),
                List.of(passage),
                dateKind,
                null,
                "Richer credit report adds a facility schedule and outlook."));
    }

    private static final String CREDIT_RATING_JSON = """
            {
              "suggested_code": "report.credit_rating",
              "family": "report",
              "identity_signals": ["Credit Rating Report"],
              "document_date": {"kind": "issue"},
              "domain_rationale": "A credit rating report states the rating, outlook, rated facilities, and covenants.",
              "fields": [
                {"key":"credit_rating","path":"economic > Credit Rating","path_root":"economic",
                 "value_kind":"text","required":true,"prompt_hint":"assigned credit rating",
                 "labels":["Credit Rating"],"domain_standard":true},
                {"key":"rating_outlook","path":"economic > Rating Outlook","path_root":"economic",
                 "value_kind":"text","required":true,"prompt_hint":"rating outlook",
                 "labels":["Outlook","Rating Outlook"],"domain_standard":true},
                {"key":"agency_scale","path":"regulatory > Agency Scale","path_root":"regulatory",
                 "value_kind":"text","required":true,"prompt_hint":"agency rating scale",
                 "labels":["Agency Scale"],"domain_standard":true}
              ],
              "tables": [
                {"name":"rated_facilities","path":"economic > Rated Facility","path_root":"economic",
                 "key_column":"facility","entity_key":"borrower","site_key":"site",
                 "domain_standard":true,
                 "columns":[
                   {"key":"facility","value_kind":"text"},
                   {"key":"borrower","value_kind":"text"},
                   {"key":"site","value_kind":"text"},
                   {"key":"tenor","value_kind":"text"},
                   {"key":"pricing","value_kind":"text"}
                 ]},
                {"name":"utilization_trend","path":"economic > Limit Utilization","path_root":"economic",
                 "key_column":"metric","period_key":"month","domain_standard":true,
                 "columns":[
                   {"key":"metric","value_kind":"text"},
                   {"key":"month","value_kind":"text"},
                   {"key":"utilization","value_kind":"text"}
                 ]}
              ],
              "clauses": [
                {"key":"financial_covenant","label":"Financial Covenant","parent_family":"covenant",
                 "aliases":["Financial Covenant"],"domain_standard":true}
              ],
              "passages": [
                {"topic":"rating_rationale","heading_terms":["Rating Rationale"],
                 "fields":["credit_rating"],"domain_standard":true}
              ]
            }
            """;
}
