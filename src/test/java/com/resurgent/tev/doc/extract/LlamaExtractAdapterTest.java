package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HTTP-stub tests for {@link LlamaExtractAdapter} against the official LlamaExtract v2
 * REST flow (files upload → extract create → extract get poll).
 */
class LlamaExtractAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<Captured> captured = new AtomicReference<>();
    private String fileResponse = "{\"id\":\"dfl-test\"}";
    private String createResponse = "{\"id\":\"ext-test\"}";
    private String getResponse = "{\"id\":\"ext-test\",\"status\":\"COMPLETED\",\"extract_result\":{}}";
    private int getFailures = 0;

    private record Captured(String method, String path, String body, String contentType) {}

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/beta/files", ex -> {
            capture(ex, "files");
            write(ex, 200, fileResponse);
        });
        server.createContext("/api/v2/extract", ex -> {
            if ("GET".equals(ex.getRequestMethod())) {
                capture(ex, "extract-get");
                if (getFailures > 0) {
                    getFailures--;
                    write(ex, 200, "{\"id\":\"ext-test\",\"status\":\"RUNNING\"}");
                } else {
                    write(ex, 200, getResponse);
                }
            } else {
                capture(ex, "extract-create");
                write(ex, 200, createResponse);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void capture(HttpExchange ex, String path) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        captured.set(new Captured(
                ex.getRequestMethod(),
                path,
                new String(body, StandardCharsets.UTF_8),
                ex.getRequestHeaders().getFirst("Content-Type")));
    }

    private static void write(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private LlamaExtractAdapter adapter() {
        return new LlamaExtractAdapter(Map.of(
                "LLAMA_CLOUD_API_KEY", "test-key",
                "LLAMA_EXTRACT_BASE_URL", baseUrl));
    }

    private Path writeOriginal() throws IOException {
        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4 test");
        return pdf;
    }

    private static JsonNode sampleSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode name = props.putObject("subject_name");
        name.put("type", "string");
        name.put("value_kind", "text");
        ObjectNode amount = props.putObject("net_worth");
        amount.put("type", "number");
        amount.put("value_kind", "money");
        return schema;
    }

    private ExtractRequest request(Path original) {
        return request(original, Map.of());
    }

    private ExtractRequest request(Path original, Map<String, String> options) {
        return new ExtractRequest(
                temp, original, List.of(1), "en", "identity.promoter_net_worth",
                "Extract net worth fields", options);
    }

    @Test
    void uploadsCreatesJobAndMapsExtractResultWithCitations() throws Exception {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("subject_name", "Ramesh Kumar Sharma");
        result.put("net_worth", 16000000);
        ObjectNode meta = MAPPER.createObjectNode();
        ObjectNode fieldMeta = meta.putObject("field_metadata");
        ObjectNode docMeta = fieldMeta.putObject("document_metadata");
        ObjectNode nameMeta = docMeta.putObject("subject_name");
        nameMeta.put("confidence", 0.99);
        ArrayNode nameCites = nameMeta.putArray("citation");
        ObjectNode nameCite = nameCites.addObject();
        nameCite.put("page", 1);
        nameCite.put("matching_text", "Mr. Ramesh Kumar Sharma");
        ObjectNode nwMeta = docMeta.putObject("net_worth");
        nwMeta.put("confidence", 0.9);
        ArrayNode nwCites = nwMeta.putArray("citation");
        ObjectNode nwCite = nwCites.addObject();
        nwCite.put("page", 1);
        nwCite.put("matching_text", "Net Worth: Rs. 1,60,00,000");
        ArrayNode boxes = nwCite.putArray("bounding_boxes");
        ObjectNode box = boxes.addObject();
        box.put("x", 72).put("y", 100).put("w", 200).put("h", 12);

        ObjectNode job = MAPPER.createObjectNode();
        job.put("id", "ext-test");
        job.put("status", "COMPLETED");
        job.set("extract_result", result);
        job.set("extract_metadata", meta);
        ObjectNode usage = job.putObject("usage");
        usage.put("credits", 12.5);
        getResponse = job.toString();

        ExtractResult out = adapter().extractFieldSchema(request(writeOriginal()), sampleSchema());

        assertThat(out.extractorId()).isEqualTo(LlamaExtractAdapter.DEFAULT_MODEL);
        assertThat(out.facts()).hasSize(2);
        FactCandidate name = out.facts().stream()
                .filter(f -> f.key().equals("subject_name")).findFirst().orElseThrow();
        assertThat(name.valueText()).isEqualTo("Ramesh Kumar Sharma");
        assertThat(name.confidence()).isEqualTo(0.99);
        assertThat(name.citations()).hasSize(1);
        assertThat(name.citations().get(0).verbatim()).contains("Ramesh Kumar Sharma");

        FactCandidate nw = out.facts().stream()
                .filter(f -> f.key().equals("net_worth")).findFirst().orElseThrow();
        assertThat(nw.valueNumber()).isEqualTo(16000000.0);
        assertThat(nw.citations().get(0).hasBbox()).isTrue();
        assertThat(nw.citations().get(0).bboxX1()).isEqualTo(272.0);

        assertThat(captured.get()).isNotNull();
    }

    @Test
    void postsOfficialExtractCreateBody() throws Exception {
        AtomicReference<String> lastCreate = new AtomicReference<>();
        HttpServer spy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        spy.createContext("/api/v1/beta/files", ex -> {
            ex.getRequestBody().readAllBytes();
            write(ex, 200, "{\"id\":\"dfl-1\"}");
        });
        spy.createContext("/api/v2/extract", ex -> {
            if ("POST".equals(ex.getRequestMethod())) {
                lastCreate.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                write(ex, 200, "{\"id\":\"ext-1\"}");
            } else {
                write(ex, 200, """
                        {"id":"ext-1","status":"COMPLETED","extract_result":{"subject_name":"X"}}
                        """);
            }
        });
        spy.start();
        try {
            LlamaExtractAdapter a = new LlamaExtractAdapter(Map.of(
                    "LLAMA_CLOUD_API_KEY", "k",
                    "LLAMA_EXTRACT_BASE_URL",
                    "http://127.0.0.1:" + spy.getAddress().getPort()));
            a.extractFieldSchema(request(writeOriginal()), sampleSchema());
            JsonNode body = MAPPER.readTree(lastCreate.get());
            assertThat(body.path("file_input").asText()).isEqualTo("dfl-1");
            JsonNode configuration = body.path("configuration");
            assertThat(configuration.path("tier").asText())
                    .isEqualTo(LlamaExtractAdapter.DEFAULT_TIER)
                    .isEqualTo("cost_effective");
            assertThat(configuration.path("cite_sources").asBoolean()).isTrue();
            assertThat(configuration.path("confidence_scores").asBoolean()).isTrue();
            assertThat(configuration.path("data_schema").path("type").asText()).isEqualTo("object");
        } finally {
            spy.stop(0);
        }
    }

    @Test
    void honorsParseTierOptionInsteadOfAgentic() throws Exception {
        AtomicReference<String> lastCreate = new AtomicReference<>();
        HttpServer spy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        spy.createContext("/api/v1/beta/files", ex -> {
            ex.getRequestBody().readAllBytes();
            write(ex, 200, "{\"id\":\"dfl-1\"}");
        });
        spy.createContext("/api/v2/extract", ex -> {
            if ("POST".equals(ex.getRequestMethod())) {
                lastCreate.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                write(ex, 200, "{\"id\":\"ext-1\"}");
            } else {
                write(ex, 200, """
                        {"id":"ext-1","status":"COMPLETED","extract_result":{"subject_name":"X"}}
                        """);
            }
        });
        spy.start();
        try {
            LlamaExtractAdapter a = new LlamaExtractAdapter(Map.of(
                    "LLAMA_CLOUD_API_KEY", "k",
                    "LLAMA_EXTRACT_BASE_URL",
                    "http://127.0.0.1:" + spy.getAddress().getPort()));
            a.extractFieldSchema(
                    request(writeOriginal(), Map.of(LlamaExtractAdapter.KEY_PARSE_TIER, "fast")),
                    sampleSchema());
            JsonNode configuration = MAPPER.readTree(lastCreate.get()).path("configuration");
            assertThat(configuration.path("tier").asText()).isEqualTo("fast");
        } finally {
            spy.stop(0);
        }
    }

    @Test
    void envTierSetsTheDefaultWhenNoOptionGiven() throws Exception {
        AtomicReference<String> lastCreate = new AtomicReference<>();
        HttpServer spy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        spy.createContext("/api/v1/beta/files", ex -> {
            ex.getRequestBody().readAllBytes();
            write(ex, 200, "{\"id\":\"dfl-1\"}");
        });
        spy.createContext("/api/v2/extract", ex -> {
            if ("POST".equals(ex.getRequestMethod())) {
                lastCreate.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                write(ex, 200, "{\"id\":\"ext-1\"}");
            } else {
                write(ex, 200, """
                        {"id":"ext-1","status":"COMPLETED","extract_result":{"subject_name":"X"}}
                        """);
            }
        });
        spy.start();
        try {
            LlamaExtractAdapter a = new LlamaExtractAdapter(Map.of(
                    "LLAMA_CLOUD_API_KEY", "k",
                    "LLAMA_EXTRACT_BASE_URL", "http://127.0.0.1:" + spy.getAddress().getPort(),
                    "LLAMA_EXTRACT_TIER", "agentic"));
            a.extractFieldSchema(request(writeOriginal()), sampleSchema());
            JsonNode configuration = MAPPER.readTree(lastCreate.get()).path("configuration");
            assertThat(configuration.path("tier").asText()).isEqualTo("agentic");
        } finally {
            spy.stop(0);
        }
    }

    @Test
    void mapsLineItemsArray() throws Exception {
        ObjectNode job = MAPPER.createObjectNode();
        job.put("status", "COMPLETED");
        ObjectNode result = job.putObject("extract_result");
        ArrayNode lines = result.putArray("line_items");
        ObjectNode line = lines.addObject();
        line.put("description", "Solar Module 550W");
        line.put("amount", 3600000);
        ObjectNode line2 = lines.addObject();
        line2.put("description", "Inverter 100kW");
        line2.put("amount", 500000);
        getResponse = job.toString();

        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode items = props.putObject("line_items");
        items.put("type", "array");

        ExtractResult out = adapter().extractFieldSchema(request(writeOriginal()), schema);
        assertThat(out.facts()).hasSize(2);
        assertThat(out.facts().get(0).lineKey()).isEqualTo("Solar Module 550W");
        assertThat(out.facts().get(0).valueNumber()).isEqualTo(3600000.0);
    }

    @Test
    void missingApiKeyFailsClearly() {
        LlamaExtractAdapter noKey = new LlamaExtractAdapter(Map.of(
                "LLAMA_EXTRACT_BASE_URL", baseUrl));
        assertThatThrownBy(() -> noKey.extractFieldSchema(
                request(writeOriginal()), sampleSchema()))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("API key is not set");
    }

    @Test
    void llamaparseApiKeyAliasIsAccepted() {
        assertThat(LlamaExtractAdapter.resolveApiKey(Map.of("llamaparse_api_key", "llx-test")))
                .isEqualTo("llx-test");
        assertThat(LlamaExtractAdapter.resolveApiKey(Map.of("LLAMAPARSE_API_KEY", "llx-upper")))
                .isEqualTo("llx-upper");
        assertThat(LlamaExtractAdapter.resolveApiKey(
                Map.of("LLAMA_CLOUD_API_KEY", "llx-canonical")))
                .isEqualTo("llx-canonical");
    }

    @Test
    void http4xxBecomesExtractException() {
        createResponse = "{\"error\":\"bad schema\"}";
        // override create path to 422 via getFailures-like: replace handler by failing create
        server.removeContext("/api/v2/extract");
        server.createContext("/api/v2/extract", ex -> {
            capture(ex, "extract-create");
            write(ex, 422, "{\"error\":\"schema mismatch\"}");
        });
        assertThatThrownBy(() -> adapter().extractFieldSchema(
                request(writeOriginal()), sampleSchema()))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("422");
    }

    @Test
    void failedJobBecomesExtractException() {
        getResponse = """
                {"id":"ext-1","status":"FAILED","error_message":"schema not supported"}
                """;
        assertThatThrownBy(() -> adapter().extractFieldSchema(
                request(writeOriginal()), sampleSchema()))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("FAILED")
                .hasMessageContaining("schema not supported");
    }

    @Test
    void missingExtractResultBecomesExtractException() {
        getResponse = "{\"id\":\"ext-1\",\"status\":\"COMPLETED\"}";
        assertThatThrownBy(() -> adapter().extractFieldSchema(
                request(writeOriginal()), sampleSchema()))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("extract_result");
    }

    @Test
    void timeoutBecomesExtractException() {
        server.removeContext("/api/v2/extract");
        server.createContext("/api/v2/extract", ex -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            write(ex, 200, "{\"id\":\"ext-1\",\"status\":\"RUNNING\"}");
        });
        assertThatThrownBy(() -> new LlamaExtractAdapter(Map.of(
                        "LLAMA_CLOUD_API_KEY", "k",
                        "LLAMA_EXTRACT_BASE_URL", baseUrl,
                        "extract.timeout_ms", "150"))
                .extractFieldSchema(request(writeOriginal()), sampleSchema()))
                .isInstanceOf(ExtractException.class);
    }

    @Test
    void costGuardRejectsWhenCreditsExceedMax() {
        ObjectNode job = MAPPER.createObjectNode();
        job.put("status", "COMPLETED");
        job.putObject("extract_result").put("subject_name", "X");
        ObjectNode usage = job.putObject("usage");
        usage.put("credits", 99.0);
        getResponse = job.toString();

        assertThatThrownBy(() -> new LlamaExtractAdapter(Map.of(
                        "LLAMA_CLOUD_API_KEY", "k",
                        "LLAMA_EXTRACT_BASE_URL", baseUrl,
                        "extract.max_usd_per_document", "1"))
                .extractFieldSchema(request(writeOriginal()), sampleSchema()))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("max_usd_per_document");
    }

    @Test
    void defaultIdIsLlamaExtract25Agentic() {
        assertThat(new LlamaExtractAdapter(Map.of("LLAMA_CLOUD_API_KEY", "k")).id())
                .isEqualTo("llama-extract-2.5-agentic");
    }
}
