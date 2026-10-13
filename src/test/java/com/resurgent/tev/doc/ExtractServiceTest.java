package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.extract.Citation;
import com.resurgent.tev.doc.extract.ExtractClient;
import com.resurgent.tev.doc.extract.ExtractException;
import com.resurgent.tev.doc.extract.ExtractRequest;
import com.resurgent.tev.doc.extract.ExtractResult;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.ExtractStats;
import com.resurgent.tev.doc.extract.FactCandidate;
import com.resurgent.tev.doc.extract.LlamaExtractAdapter;
import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Extract orchestration (T7): parse_run identity, derived net worth, quotation
 * line items, re-extract replace, failure leaves no half-written facts.
 */
class ExtractServiceTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path dbPath;
    private long documentVersionId;
    private final long mandateId = 1L;

    /** Minimal offline extractor controlled per test. */
    static class FakeExtractClient implements ExtractClient {
        final List<FactCandidate> facts = new ArrayList<>();
        final ObjectNode payload = MAPPER.createObjectNode();
        boolean fail;
        ExtractStats stats = ExtractStats.empty();
        ExtractRequest lastRequest;

        @Override
        public String id() {
            return "fake-extract";
        }

        @Override
        public ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema)
                throws ExtractException {
            lastRequest = req;
            if (fail) {
                throw new ExtractException("simulated extractor failure");
            }
            return new ExtractResult(id(), payload, List.copyOf(facts), List.of(), stats);
        }
    }

    private FakeExtractClient client;

    static DocTypePack netWorthPack() {
        return new DocTypePack(
                "identity.promoter_net_worth", "1.0.0", "certificate", "certificate",
                List.of("general"), List.of(),
                List.of(
                        PackField.required("subject_name", "identity > Legal Name",
                                "identity", ValueKind.TEXT),
                        PackField.required("as_of_date", "economic > Promoter Net Worth > As Of",
                                "economic", ValueKind.DATE),
                        PackField.required("assets_total",
                                "economic > Promoter Net Worth > Assets", "economic",
                                ValueKind.MONEY),
                        PackField.required("liabilities_total",
                                "economic > Promoter Net Worth > Liabilities", "economic",
                                ValueKind.MONEY),
                        PackField.required("net_worth",
                                "economic > Promoter Net Worth > Net Worth", "economic",
                                ValueKind.MONEY)),
                List.of(), List.of(), List.of());
    }

    static DocTypePack quotationPack() {
        return new DocTypePack(
                "quotation.vendor", "1.0.0", "quotation", "quotation",
                List.of("general"), List.of(),
                List.of(
                        PackField.required("vendor_name", "identity > Legal Name",
                                "identity", ValueKind.TEXT),
                        PackField.required("gross_total",
                                "economic > Project Cost > Quoted Gross", "economic",
                                ValueKind.MONEY),
                        PackField.optional("subtotal", "economic > Project Cost > Subtotal",
                                "economic", ValueKind.MONEY)),
                List.of(Map.of(
                        "name", "line_items",
                        "path", "economic > Project Cost > Line Item",
                        "path_root", "economic",
                        "fields", List.of(
                                Map.of("key", "description", "value_kind", "text"),
                                Map.of("key", "amount", "value_kind", "money")))),
                List.of(), List.of());
    }

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(mandateId, "nw.pdf", "hash-nw", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long documentId = docs.insertDocument(mandateId, "NW", "certificate", null);
            documentVersionId = docs.insertDocumentVersion(documentId, "v1", "nw.pdf",
                    "hash-nw", ".pdf", "certificate", 1, false, sourceFileId, null);
        }
        client = new FakeExtractClient();
    }

    private void givenNetWorthWithoutStatedNet() {
        client.facts.add(new FactCandidate("subject_name", ValueKind.TEXT)
                .valueText("Ramesh Sharma").confidence(0.95)
                .extractionMethod("llm_field")
                .addCitation(Citation.of(1, "Name: Ramesh Sharma")));
        client.facts.add(new FactCandidate("as_of_date", ValueKind.DATE)
                .valueText("31.03.2026").confidence(0.9)
                .extractionMethod("llm_field"));
        client.facts.add(new FactCandidate("assets_total", ValueKind.MONEY)
                .valueText("Rs. 1,00,00,000").confidence(0.9)
                .extractionMethod("llm_field")
                .addCitation(Citation.of(1, "Total Assets Rs. 1,00,00,000")));
        client.facts.add(new FactCandidate("liabilities_total", ValueKind.MONEY)
                .valueText("Rs. 25,00,000").confidence(0.9)
                .extractionMethod("llm_field")
                .addCitation(Citation.of(1, "Total Liabilities Rs. 25,00,000")));
    }

    @Test
    void derivedNetWorthComputedWhenNotStated() throws Exception {
        givenNetWorthWithoutStatedNet();
        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, documentVersionId, mandateId, netWorthPack(), client, "1");

        assertThat(outcome.status()).isEqualTo("success");
        assertThat(outcome.derivedCount()).isEqualTo(1);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository facts = new FactRepository(db.connection());
            List<FactRow> rows = facts.query(FactQuery.forMandate(mandateId)
                    .withVersion(documentVersionId));
            FactRow netWorth = rows.stream()
                    .filter(r -> "net_worth".equals(r.fieldKey())).findFirst().orElseThrow();
            assertThat(netWorth.extractionMethod()).isEqualTo("derived");
            assertThat(netWorth.absoluteAmount()).isEqualTo("7500000");
            assertThat(netWorth.evidence()).anySatisfy(e -> {
                assertThat(e.role()).isEqualTo("derived_from");
                assertThat(e.verbatim()).isEqualTo("assets_total");
            });
            assertThat(netWorth.evidence()).anySatisfy(e -> {
                assertThat(e.role()).isEqualTo("derived_from");
                assertThat(e.verbatim()).isEqualTo("liabilities_total");
            });
        }
    }

    @Test
    void statedNetWorthIsNotDuplicatedByDerivation() throws Exception {
        givenNetWorthWithoutStatedNet();
        client.facts.add(new FactCandidate("net_worth", ValueKind.MONEY)
                .valueText("Rs. 75,00,000").confidence(0.97)
                .extractionMethod("llm_field"));

        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, documentVersionId, mandateId, netWorthPack(), client, "1");
        assertThat(outcome.derivedCount()).isEqualTo(0);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository facts = new FactRepository(db.connection());
            List<FactRow> rows = facts.query(FactQuery.forMandate(mandateId)
                    .withVersion(documentVersionId));
            List<FactRow> nets = rows.stream()
                    .filter(r -> "net_worth".equals(r.fieldKey())).toList();
            assertThat(nets).hasSize(1);
            assertThat(nets.get(0).extractionMethod()).isEqualTo("llm_field");
        }
    }

    @Test
    void quotationLineItemsBecomeOneFactPerLinePlusParentTotals() throws Exception {
        client.facts.add(new FactCandidate("vendor_name", ValueKind.TEXT)
                .valueText("Acme Supplies").confidence(0.9)
                .extractionMethod("llm_field"));
        client.facts.add(new FactCandidate("subtotal", ValueKind.MONEY)
                .valueText("Rs. 10,000").confidence(0.9)
                .extractionMethod("llm_field"));
        client.facts.add(new FactCandidate("gross_total", ValueKind.MONEY)
                .valueText("Rs. 12,000").confidence(0.9)
                .extractionMethod("llm_field"));
        client.facts.add(new FactCandidate("line_items", ValueKind.TEXT)
                .valueText("Solar panels 10kW")
                .lineKey("Solar panels 10kW")
                .valueNumber(10000.0)
                .confidence(0.85)
                .extractionMethod("llm_field")
                .addCitation(Citation.of(1, "Solar panels 10kW")));
        ArrayNode items = payloadItems();
        ObjectNode row = items.addObject();
        row.put("description", "Inverter");
        row.put("amount", "2,000");

        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, documentVersionId, mandateId, quotationPack(), client, "1");
        assertThat(outcome.lineItemCount()).isEqualTo(2);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository facts = new FactRepository(db.connection());
            List<FactRow> rows = facts.query(FactQuery.forMandate(mandateId)
                    .withVersion(documentVersionId));
            List<FactRow> lines = rows.stream()
                    .filter(r -> "line_items".equals(r.fieldKey())).toList();
            assertThat(lines).hasSize(2);
            assertThat(lines).allSatisfy(r -> {
                assertThat(r.ontologyPath()).isEqualTo("economic > Project Cost > Line Item");
                assertThat(r.pathRoot()).isEqualTo("economic");
                assertThat(r.subjectEntity()).isEqualTo("Acme Supplies");
            });

            List<String> lineKeys = new ArrayList<>();
            try (var st = db.connection().createStatement();
                    var rs = st.executeQuery(
                            "SELECT line_key FROM fact WHERE parse_run_id = "
                                    + outcome.parseRunId()
                                    + " AND field_key = 'line_items' ORDER BY fact_id")) {
                while (rs.next()) {
                    lineKeys.add(rs.getString(1));
                }
            }
            assertThat(lineKeys).containsExactly("Solar panels 10kW", "Inverter");

            assertThat(rows).anySatisfy(r -> assertThat(r.fieldKey()).isEqualTo("gross_total"));
            assertThat(rows).anySatisfy(r -> assertThat(r.fieldKey()).isEqualTo("subtotal"));
        }
    }

    private ArrayNode payloadItems() {
        JsonNode existing = client.payload.get("line_items");
        if (existing instanceof ArrayNode arr) {
            return arr;
        }
        ArrayNode arr = MAPPER.createArrayNode();
        client.payload.set("line_items", arr);
        return arr;
    }

    @Test
    void reExtractReplacesFactSet() throws Exception {
        givenNetWorthWithoutStatedNet();
        ExtractService service = new ExtractService();
        ExtractService.ExtractOutcome first = service.extract(
                dbPath, documentVersionId, mandateId, netWorthPack(), client, "1");
        assertThat(first.factsTotal()).isGreaterThanOrEqualTo(4);

        client.facts.clear();
        client.facts.add(new FactCandidate("subject_name", ValueKind.TEXT)
                .valueText("Ramesh Sharma").confidence(0.99).extractionMethod("llm_field"));
        ExtractService.ExtractOutcome second = service.extract(
                dbPath, documentVersionId, mandateId, netWorthPack(), client, "1");

        assertThat(second.parseRunId()).isEqualTo(first.parseRunId());
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository facts = new FactRepository(db.connection());
            assertThat(facts.countFactsForParseRun(second.parseRunId())).isEqualTo(1);
            List<FactRow> rows = facts.query(FactQuery.forMandate(mandateId)
                    .withVersion(documentVersionId));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).fieldKey()).isEqualTo("subject_name");
        }
    }

    @Test
    void extractorFailureMarksRunFailedWithoutFacts() throws Exception {
        givenNetWorthWithoutStatedNet();
        client.fail = true;
        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, documentVersionId, mandateId, netWorthPack(), client, "1");

        assertThat(outcome.status()).isEqualTo("failed");
        assertThat(outcome.factsTotal()).isZero();

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            FactRepository facts = new FactRepository(db.connection());
            assertThat(facts.countFactsForParseRun(outcome.parseRunId())).isZero();
            try (var st = db.connection().createStatement();
                    var rs = st.executeQuery(
                            "SELECT status FROM parse_run WHERE parse_run_id = "
                                    + outcome.parseRunId())) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("failed");
            }
        }
    }

    @Test
    void emptyExtractIsPartialAndDoesNotMarkVersionExtracted() throws Exception {
        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, documentVersionId, mandateId, netWorthPack(), client, "1");
        assertThat(outcome.status()).isEqualTo("partial");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            try (var st = db.connection().createStatement();
                    var rs = st.executeQuery(
                            "SELECT ingest_status FROM document_version WHERE document_version_id = "
                                    + documentVersionId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNotEqualTo("extracted");
            }
        }
    }

    @Test
    void configHashFollowsSpecFormula() {
        assertThat(ExtractService.configHash(netWorthPack(), "bundle-2.5"))
                .isEqualTo("extract:identity.promoter_net_worth@1.0.0:bundle-2.5");
    }

    private List<String[]> usageRows(long parseRunId) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath);
                var st = db.connection().createStatement();
                var rs = st.executeQuery(
                        "SELECT model_id, calls, prompt_tokens, completion_tokens, cost_usd,"
                                + " cost_missing, latency_ms_total FROM llm_usage"
                                + " WHERE parse_run_id = " + parseRunId + " AND stage = 'doc-extract'"
                                + " ORDER BY model_id")) {
            while (rs.next()) {
                rows.add(new String[] {
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)});
            }
        }
        return rows;
    }

    @Test
    void recordsExtractAndParseCreditsInLlmUsage() throws Exception {
        givenNetWorthWithoutStatedNet();
        client.stats = new ExtractStats(1, 0, 100, 50, 45.0, 1200L);
        Map<String, String> options = Map.of(
                LlamaExtractAdapter.KEY_PARSE_JOB_ID, "pjb-1",
                LlamaExtractAdapter.KEY_PARSE_TIER, "agentic",
                LlamaExtractAdapter.KEY_PARSE_CREDITS, "30.0",
                LlamaExtractAdapter.KEY_PARSE_LATENCY_MS, "900");

        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, documentVersionId, mandateId, netWorthPack(), client, "1", null, options);

        List<String[]> rows = usageRows(outcome.parseRunId());
        assertThat(rows).extracting(r -> r[0])
                .containsExactly("fake-extract", "llama-parse:agentic");

        String[] extract = rows.get(0);
        assertThat(extract[1]).isEqualTo("1");
        assertThat(extract[2]).isEqualTo("100");
        assertThat(extract[3]).isEqualTo("50");
        assertThat(Double.parseDouble(extract[4])).isEqualTo(45.0);
        assertThat(extract[5]).isEqualTo("0");
        assertThat(extract[6]).isEqualTo("1200");

        String[] parse = rows.get(1);
        assertThat(Double.parseDouble(parse[4])).isEqualTo(30.0);
        assertThat(parse[5]).isEqualTo("0");
        assertThat(parse[6]).isEqualTo("900");
    }

    @Test
    void reExtractReplacesUsageRows() throws Exception {
        givenNetWorthWithoutStatedNet();
        client.stats = new ExtractStats(1, 0, 0, 0, 10.0, 100L);
        Map<String, String> options = Map.of(
                LlamaExtractAdapter.KEY_PARSE_JOB_ID, "pjb-1",
                LlamaExtractAdapter.KEY_PARSE_TIER, "agentic",
                LlamaExtractAdapter.KEY_PARSE_CREDITS, "30.0");
        ExtractService service = new ExtractService();
        ExtractService.ExtractOutcome first = service.extract(
                dbPath, documentVersionId, mandateId, netWorthPack(), client, "1", null, options);
        assertThat(usageRows(first.parseRunId())).hasSize(2);

        // Re-run with a cheaper parse and an extractor that reports no credits: the old
        // llama-parse:agentic row must not linger.
        client.stats = ExtractStats.empty();
        ExtractService.ExtractOutcome second = service.extract(
                dbPath, documentVersionId, mandateId, netWorthPack(), client, "1", null, null);
        assertThat(second.parseRunId()).isEqualTo(first.parseRunId());
        assertThat(usageRows(second.parseRunId())).isEmpty();
    }

    @Test
    void pageSelectionIsValidatedAndCarriedOnTheRequest() throws Exception {
        client.facts.add(new FactCandidate("subject_name", ValueKind.TEXT)
                .valueText("Ramesh Sharma").confidence(0.95).extractionMethod("llm_field"));
        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, documentVersionId, mandateId, netWorthPack(), client, "1", null, null,
                List.of(1));

        assertThat(outcome.status()).isEqualTo("success");
        assertThat(client.lastRequest.pageIndices()).containsExactly(1);
    }

    @Test
    void outOfRangePageIsRejected() {
        assertThatThrownBy(() -> new ExtractService().extract(
                dbPath, documentVersionId, mandateId, netWorthPack(), client, "1", null, null,
                List.of(9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }
}
