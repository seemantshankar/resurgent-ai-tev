package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Path-shaped LlamaExtract citations fall back to value text, then existing
 * quote resolution runs against layout text (issue #197).
 */
class VerbatimQuoteRepairTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATH_QUOTE =
            "profit_and_loss_account.0.sections.2.items.12.label";

    @TempDir
    Path temp;

    private HttpServer server;
    private String baseUrl;
    private String getResponse;

    private WorkspaceDatabase db;
    private ConnectionHandle handle;

    /** Holds open workspace objects for one test. */
    private record ConnectionHandle(
            FactRepository facts,
            DocumentRepository docs,
            long documentVersionId,
            long parseRunId,
            long pageId) {}

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/beta/files", ex -> {
            ex.getRequestBody().readAllBytes();
            write(ex, 200, "{\"id\":\"dfl-test\"}");
        });
        server.createContext("/api/v2/extract", ex -> {
            ex.getRequestBody().readAllBytes();
            if ("GET".equals(ex.getRequestMethod())) {
                write(ex, 200, getResponse);
            } else {
                write(ex, 200, "{\"id\":\"ext-test\"}");
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        db = WorkspaceDatabase.open(temp.resolve("workspace.db"));
        FactRepository facts = new FactRepository(db.connection());
        DocumentRepository docs = new DocumentRepository(db.connection());
        WorkspaceRepository repo = new WorkspaceRepository(db.connection());
        long sourceFileId = repo.insertSourceFile(1L, "nw.pdf", "hash-nw", "pdf",
                "2026-01-01T00:00:00Z", "0.1.0", null);
        long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0-doc-extract",
                "extract:test@1:1", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z",
                "success", null);
        long documentId = docs.insertDocument(1L, "NW", "certificate", null);
        long documentVersionId = docs.insertDocumentVersion(documentId, "v1", "nw.pdf",
                "hash-nw", ".pdf", "certificate", 1, false, sourceFileId, null);
        long pageId = docs.insertPage(documentVersionId, 1, 600, 800, "hash-nw/pages/p001.png");
        handle = new ConnectionHandle(facts, docs, documentVersionId, parseRunId, pageId);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void fallbackQuoteResolvesWhenLayoutContainsTheValue() throws Exception {
        Citation mapped = mapPathCitation("16000000");
        insertLayout("Certified net worth is 16000000 as on date");

        handle.facts().replaceFacts(handle.parseRunId(), handle.documentVersionId(),
                List.of(moneyFact(mapped)));

        FactRow.EvidenceRow evidence = quoteEvidence();
        assertThat(evidence.verbatim()).isEqualTo("16000000");
        assertThat(evidence.verbatim()).doesNotContain(PATH_QUOTE);
        assertThat(evidence.resolution()).isEqualTo("resolved");
    }

    @Test
    void fallbackQuoteStaysMissingWhenLayoutDoesNotContainTheValue() throws Exception {
        Citation mapped = mapPathCitation("16000000");
        insertLayout("This certificate states the position of the subject.");

        handle.facts().replaceFacts(handle.parseRunId(), handle.documentVersionId(),
                List.of(moneyFact(mapped)));

        FactRow.EvidenceRow evidence = quoteEvidence();
        assertThat(evidence.verbatim()).isEqualTo("16000000");
        assertThat(evidence.verbatim()).doesNotContain(PATH_QUOTE);
        assertThat(evidence.resolution()).isEqualTo("missing");
    }

    @Test
    void sanitizerSourceHasNoDocumentTypeSchemaKeyNames() throws Exception {
        Path extractDir = Path.of("src/main/java/com/resurgent/tev/doc/extract");
        assertThat(extractDir).isDirectory();
        StringBuilder sources = new StringBuilder();
        try (var files = Files.walk(extractDir)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            sources.append(Files.readString(path)).append('\n');
                        } catch (IOException e) {
                            throw new RuntimeException(path.toString(), e);
                        }
                    });
        }
        String source = sources.toString();
        assertThat(source)
                .doesNotContain("profit_and_loss")
                .doesNotContain("pvsyst")
                .doesNotContain("land_deed")
                .doesNotContain("net_worth_certificate")
                .doesNotContain("promoter_net_worth")
                .doesNotContain("tax_audit")
                .doesNotContain("audited_summary");
    }

    private Citation mapPathCitation(String numericValue) throws Exception {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("net_worth", Long.parseLong(numericValue));
        ObjectNode meta = MAPPER.createObjectNode();
        ObjectNode nwCite = meta.putObject("field_metadata")
                .putObject("document_metadata")
                .putObject("net_worth")
                .putArray("citation")
                .addObject();
        nwCite.put("page", 1);
        nwCite.put("matching_text", PATH_QUOTE);
        ObjectNode job = MAPPER.createObjectNode();
        job.put("status", "COMPLETED");
        job.set("extract_result", result);
        job.set("extract_metadata", meta);
        getResponse = job.toString();

        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4 test");
        ExtractResult out = new LlamaExtractAdapter(Map.of(
                "LLAMA_CLOUD_API_KEY", "test-key",
                "LLAMA_EXTRACT_BASE_URL", baseUrl))
                .extractFieldSchema(new ExtractRequest(
                        temp, pdf, List.of(1), "en", "identity.promoter_net_worth",
                        "Extract net worth fields", Map.of()),
                        sampleSchema());
        FactCandidate nw = out.facts().stream()
                .filter(f -> f.key().equals("net_worth")).findFirst().orElseThrow();
        assertThat(nw.citations()).hasSize(1);
        return nw.citations().get(0);
    }

    private void insertLayout(String text) throws Exception {
        long blockId = handle.docs().insertBlock(handle.pageId(), 0, "paragraph",
                72.0, 600.0, 500.0, 640.0, text, text, 0, "test");
        handle.docs().insertSpan(blockId, 0, text, 72.0, 600.0, 500.0, 620.0, 0.99);
    }

    private FactRow.EvidenceRow quoteEvidence() throws Exception {
        List<FactRow> rows = handle.facts().query(
                FactQuery.forMandate(1L).withVersion(handle.documentVersionId()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).evidence()).isNotEmpty();
        return rows.get(0).evidence().get(0);
    }

    private static FactRepository.FactWrite moneyFact(Citation citation) {
        return new FactRepository.FactWrite(
                "net_worth", "economic > Test > net_worth", "economic", "Sharma",
                "money", "16000000", 16000000.0, null, null, "INR", null, "16000000",
                null, "llm_field", "test-extract", 0.9, "stated",
                null, null, List.of(citation), List.of());
    }

    private static JsonNode sampleSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode amount = props.putObject("net_worth");
        amount.put("type", "number");
        amount.put("value_kind", "money");
        return schema;
    }

    private static void write(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
