package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CI tracer (#202): recorded Shape 1 mining JSON through PackInducer, Shape 2
 * pack schema, and extract, so a schedule row lands as {@code table.column}
 * facts with {@code line_key} and kinds after normalizers.
 */
class InductionScheduleExtractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MANDATE = 1L;

    /**
     * Nested Shape 1 mining JSON. {@code amount} omits {@code value_kind} so
     * #198 infers quantity from JSON number; {@code consideration} is explicit
     * money. #200 must rewrite the nested row array into pack {@code tables[]}.
     */
    private static final String NESTED_SHAPE1_JSON = """
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
                   "amount":{"type":"number"},
                   "consideration":{"value_kind":"money"}
                 }}}
              ]
            }
            """;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long documentVersionId;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(MANDATE, "schedule.pdf", "hash-schedule",
                    "pdf", "2026-01-01T00:00:00Z", "0.1.0", null);
            long documentId = docs.insertDocument(MANDATE, "Site Schedule", "land_document", null);
            documentVersionId = docs.insertDocumentVersion(documentId, "v1", "schedule.pdf",
                    "hash-schedule", ".pdf", "land_document", 1, false, sourceFileId, null);
            docs.insertPage(documentVersionId, 1, 600, 800, "hash-schedule/pages/p001.png");
        }
    }

    @Test
    void nestedMiningFixtureInducesShape2PackAndExtractsRowFacts() throws Exception {
        PackInducer.InduceResult induced = new PackInducer().induce(new PackInducer.InduceRequest(
                List.of(new PackInducer.InduceDocument(
                        "d1",
                        "land_document",
                        "Site Schedule",
                        List.of(
                                "Site Schedule",
                                "Grantor",
                                "Line Items",
                                "Description",
                                "Amount",
                                "Consideration"),
                        Set.of())),
                "general",
                "land_document",
                "demo.schedule",
                "legal",
                nestedMining(),
                new PackStore(tempDir.resolve("packs")),
                0.6,
                1,
                true));

        assertThat(induced.allGatesPassed())
                .withFailMessage("induction gates failed: %s", failedGates(induced))
                .isTrue();

        DocTypePack pack = induced.pack();
        assertThat(pack.tables()).hasSize(1);
        assertThat(pack.tables().getFirst().get("name")).isEqualTo("line_items");
        assertThat(pack.tables().getFirst().get("key_column")).isEqualTo("description");

        JsonNode schema = pack.toExtractSchema();
        JsonNode columns = schema.path("properties").path("line_items").path("items")
                .path("properties");
        assertThat(schema.path("properties").path("line_items").path("type").asText())
                .isEqualTo("array");
        assertThat(columns.path("description").path("line_key").asBoolean()).isTrue();
        assertThat(columns.path("amount").path("value_kind").asText()).isEqualTo("quantity");
        assertThat(columns.path("consideration").path("value_kind").asText()).isEqualTo("money");

        FixtureExtractClient client = new FixtureExtractClient();
        client.putFixture("demo.schedule", MAPPER.readTree("""
                {
                  "doc_type_code": "demo.schedule",
                  "tables": {
                    "line_items": [
                      {"description": "Widget",
                       "amount": {"value": "4.5 acre", "quote": "Widget 4.5 acre", "page": 1},
                       "consideration": {"value": "Rs 10 lakh", "quote": "Widget price 10 lakh", "page": 1}},
                      {"description": "Gadget",
                       "amount": {"value": "2 guntha", "quote": "Gadget 2 guntha", "page": 1},
                       "consideration": {"value": "Rs 3 lakh", "quote": "Gadget price 3 lakh", "page": 1}}
                    ]
                  }
                }
                """));

        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, documentVersionId, MANDATE, pack, client, "test");
        assertThat(outcome.status()).isEqualTo("success");

        List<FactRow> facts = facts();
        assertThat(facts).extracting(FactRow::fieldKey)
                .contains("line_items.amount", "line_items.consideration")
                .doesNotContain("amount", "consideration", "description", "line_items");

        FactRow amount = cell(facts, "line_items.amount", "Widget");
        assertThat(amount.valueKind()).isEqualTo("quantity");
        assertThat(amount.valueText()).isEqualTo("4.5 acre");
        assertThat(amount.valueNumber()).isEqualTo(4.5);
        assertThat(amount.unit()).isEqualTo("acre");
        assertThat(amount.scale()).isEqualTo("unit");

        FactRow price = cell(facts, "line_items.consideration", "Widget");
        assertThat(price.valueKind()).isEqualTo("money");
        assertThat(price.valueText()).isEqualTo("Rs 10 lakh");
        assertThat(price.currency()).isEqualTo("INR");
        assertThat(price.scale()).isEqualTo("lakh");

        FactRow otherAmount = cell(facts, "line_items.amount", "Gadget");
        assertThat(otherAmount.valueKind()).isEqualTo("quantity");
        assertThat(otherAmount.valueText()).isEqualTo("2 guntha");
    }

    private static PackMiningClient nestedMining() {
        return new PackMiningClient() {
            @Override
            public String id() {
                return "fixture-mining";
            }

            @Override
            public MiningResult mine(MiningRequest request) {
                return LlmPackMiningClient.interpret(NESTED_SHAPE1_JSON, request);
            }
        };
    }

    private static String failedGates(PackInducer.InduceResult induced) {
        return induced.gates().stream()
                .filter(gate -> !gate.passed())
                .map(gate -> gate.name() + ": " + gate.message())
                .collect(Collectors.joining("; "));
    }

    private List<FactRow> facts() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection()).query(FactQuery.forMandate(MANDATE));
        }
    }

    private static FactRow cell(List<FactRow> facts, String fieldKey, String lineKey) {
        return facts.stream()
                .filter(f -> fieldKey.equals(f.fieldKey()) && lineKey.equals(f.lineKey()))
                .findFirst()
                .orElseThrow();
    }
}
