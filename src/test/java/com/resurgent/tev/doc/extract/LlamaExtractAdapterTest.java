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
    void pathShapedMatchingTextFallsBackToExtractedValueText() throws Exception {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("subject_name", "Ramesh Kumar Sharma");
        ObjectNode meta = MAPPER.createObjectNode();
        ObjectNode fieldMeta = meta.putObject("field_metadata");
        ObjectNode docMeta = fieldMeta.putObject("document_metadata");
        ObjectNode nameMeta = docMeta.putObject("subject_name");
        ArrayNode nameCites = nameMeta.putArray("citation");
        ObjectNode nameCite = nameCites.addObject();
        nameCite.put("page", 1);
        nameCite.put("matching_text",
                "profit_and_loss_account.0.sections.2.items.12.label");

        ObjectNode job = MAPPER.createObjectNode();
        job.put("id", "ext-test");
        job.put("status", "COMPLETED");
        job.set("extract_result", result);
        job.set("extract_metadata", meta);
        getResponse = job.toString();

        ExtractResult out = adapter().extractFieldSchema(request(writeOriginal()), sampleSchema());
        FactCandidate name = out.facts().stream()
                .filter(f -> f.key().equals("subject_name")).findFirst().orElseThrow();
        assertThat(name.citations()).hasSize(1);
        assertThat(name.citations().get(0).verbatim()).isEqualTo("Ramesh Kumar Sharma");
    }

    @Test
    void pathShapedMatchingTextOnANumberFallsBackToPlainFigureText() throws Exception {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("net_worth", 16000000);
        ObjectNode meta = MAPPER.createObjectNode();
        ObjectNode docMeta = meta.putObject("field_metadata").putObject("document_metadata");
        ObjectNode nwMeta = docMeta.putObject("net_worth");
        ObjectNode nwCite = nwMeta.putArray("citation").addObject();
        nwCite.put("page", 1);
        nwCite.put("matching_text", "headline_figures.0.sections.2.items.12.value");
        ObjectNode box = nwCite.putArray("bounding_boxes").addObject();
        box.put("x", 72).put("y", 100).put("w", 200).put("h", 12);

        ObjectNode job = MAPPER.createObjectNode();
        job.put("status", "COMPLETED");
        job.set("extract_result", result);
        job.set("extract_metadata", meta);
        getResponse = job.toString();

        ExtractResult out = adapter().extractFieldSchema(request(writeOriginal()), sampleSchema());
        FactCandidate nw = out.facts().stream()
                .filter(f -> f.key().equals("net_worth")).findFirst().orElseThrow();
        assertThat(nw.valueText()).isEqualTo("16000000");
        assertThat(nw.citations()).hasSize(1);
        assertThat(nw.citations().get(0).verbatim()).isEqualTo("16000000");
        assertThat(nw.citations().get(0).hasBbox()).isTrue();
        assertThat(nw.citations().get(0).bboxX1()).isEqualTo(272.0);
    }

    @Test
    void pathShapedMatchingTextWithNoValueOmitsTheCitation() throws Exception {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("subject_name", "");
        ObjectNode meta = MAPPER.createObjectNode();
        ObjectNode docMeta = meta.putObject("field_metadata").putObject("document_metadata");
        ObjectNode nameCite = docMeta.putObject("subject_name").putArray("citation").addObject();
        nameCite.put("page", 1);
        nameCite.put("matching_text", "identity_block.0.fields.3.label");

        ObjectNode job = MAPPER.createObjectNode();
        job.put("status", "COMPLETED");
        job.set("extract_result", result);
        job.set("extract_metadata", meta);
        getResponse = job.toString();

        ExtractResult out = adapter().extractFieldSchema(request(writeOriginal()), sampleSchema());
        FactCandidate name = out.facts().stream()
                .filter(f -> f.key().equals("subject_name")).findFirst().orElseThrow();
        assertThat(name.citations()).isEmpty();
        assertThat(out.citations()).isEmpty();
    }

    @Test
    void schedulePathsFromDifferentPackFamiliesFallBackWithoutTypeSwitches() throws Exception {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("subject_name", "Certificate holder");
        result.put("net_worth", 2500000);
        ObjectNode amount = MAPPER.createObjectNode();
        amount.put("type", "number");
        amount.put("value_kind", "quantity");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.set("subject_name", sampleSchema().path("properties").path("subject_name"));
        props.set("net_worth", sampleSchema().path("properties").path("net_worth"));
        ObjectNode yield = props.putObject("specific_yield");
        yield.put("type", "number");
        yield.put("value_kind", "quantity");
        ObjectNode area = props.putObject("total_area");
        area.put("type", "number");
        area.put("value_kind", "quantity");
        result.put("specific_yield", 1842.5);
        result.put("total_area", 4.5);

        ObjectNode docMeta = MAPPER.createObjectNode();
        cite(docMeta, "subject_name", "assets.0.amount", 1);
        cite(docMeta, "net_worth", "assets.0.amount", 1);
        cite(docMeta, "specific_yield", "monthly_generation.0.e_grid", 1);
        cite(docMeta, "total_area", "parcels.0.area", 1);

        ObjectNode job = MAPPER.createObjectNode();
        job.put("status", "COMPLETED");
        job.set("extract_result", result);
        ObjectNode meta = MAPPER.createObjectNode();
        meta.putObject("field_metadata").set("document_metadata", docMeta);
        job.set("extract_metadata", meta);
        getResponse = job.toString();

        ExtractResult out = adapter().extractFieldSchema(request(writeOriginal()), schema);
        assertThat(verbatimOf(out, "subject_name")).isEqualTo("Certificate holder");
        assertThat(verbatimOf(out, "net_worth")).isEqualTo("2500000");
        assertThat(verbatimOf(out, "specific_yield")).isEqualTo("1842.5");
        assertThat(verbatimOf(out, "total_area")).isEqualTo("4.5");
        for (FactCandidate fact : out.facts()) {
            for (Citation citation : fact.citations()) {
                assertThat(citation.verbatim())
                        .doesNotContain("assets.0.amount")
                        .doesNotContain("monthly_generation.0.e_grid")
                        .doesNotContain("parcels.0.area");
            }
        }
    }

    @Test
    void numberFactsKeepPlainFigureTextNotScientificNotation() throws Exception {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("net_worth", 571178252.43);
        ObjectNode job = MAPPER.createObjectNode();
        job.put("id", "ext-test");
        job.put("status", "COMPLETED");
        job.set("extract_result", result);
        getResponse = job.toString();

        ExtractResult out = adapter().extractFieldSchema(request(writeOriginal()), sampleSchema());
        FactCandidate nw = out.facts().stream()
                .filter(f -> f.key().equals("net_worth")).findFirst().orElseThrow();
        assertThat(nw.valueText()).isEqualTo("571178252.43");
        assertThat(nw.valueNumber()).isEqualTo(571178252.43);
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
        assertThat(capturedTierFor(Map.of(LlamaExtractAdapter.KEY_PARSE_TIER, "agentic_plus")))
                .isEqualTo("agentic_plus");
        assertThat(capturedTierFor(Map.of(LlamaExtractAdapter.KEY_PARSE_TIER, "cost_effective")))
                .isEqualTo("cost_effective");
    }

    /**
     * {@code fast} exists in LlamaParse but is not an accepted LlamaExtract
     * {@code configuration.tier}, so mirroring it verbatim fails the call with HTTP 422.
     */
    @Test
    void mapsParseFastToCheapestExtractTier() throws Exception {
        assertThat(capturedTierFor(Map.of(LlamaExtractAdapter.KEY_PARSE_TIER, "fast")))
                .isEqualTo("cost_effective");
    }

    @Test
    void rejectsUnsupportedTierWordByFallingBackToDefault() throws Exception {
        assertThat(capturedTierFor(Map.of(LlamaExtractAdapter.KEY_PARSE_TIER, "hyper")))
                .isEqualTo(LlamaExtractAdapter.DEFAULT_TIER);
    }

    private String capturedTierFor(Map<String, String> options) throws Exception {
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
            a.extractFieldSchema(request(writeOriginal(), options), sampleSchema());
            return MAPPER.readTree(lastCreate.get()).path("configuration").path("tier").asText();
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
    void costGuardRejectsAfterCompletionWhenUsageExceedsMax() {
        ObjectNode job = MAPPER.createObjectNode();
        job.put("status", "COMPLETED");
        job.putObject("extract_result").put("subject_name", "X");
        ObjectNode usage = job.putObject("usage");
        usage.put("credits", 9000.0);
        getResponse = job.toString();

        // estimate (1 page × 3 credits/page = 3 credits ≈ $0.0036) fits under the cap,
        // so the job is submitted and the post-call usage verification is what rejects:
        // 9,000 credits × $0.0012 ≈ $10.80 > cap $10
        assertThatThrownBy(() -> new LlamaExtractAdapter(Map.of(
                        "LLAMA_CLOUD_API_KEY", "k",
                        "LLAMA_EXTRACT_BASE_URL", baseUrl,
                        "extract.max_usd_per_document", "10"))
                .extractFieldSchema(request(writeOriginal()), sampleSchema()))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("usage")
                .hasMessageContaining("credits")
                .hasMessageContaining("max_usd_per_document");
    }

    @Test
    void costGuardRefusesBeforeSubmitWhenEstimateExceedsCap() {
        // 3 pages × 3 credits/page (cost_effective) = 9 credits × $0.0012 ≈ $0.0108
        // > cap $0.01 — refused with zero spend
        assertThatThrownBy(() -> new LlamaExtractAdapter(Map.of(
                        "LLAMA_CLOUD_API_KEY", "k",
                        "LLAMA_EXTRACT_BASE_URL", baseUrl,
                        "extract.max_usd_per_document", "0.01"))
                .extractFieldSchema(requestPages(writeOriginal(), List.of(1, 2, 3)),
                        sampleSchema()))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("estimated spend")
                .hasMessageContaining("max_usd_per_document");
        assertThat(captured.get()).isNull();
    }

    @Test
    void costGuardPassesThroughWhenEstimateUnderCap() throws Exception {
        // 9 credits ≈ $0.0108 < cap $0.05
        ExtractResult out = new LlamaExtractAdapter(Map.of(
                        "LLAMA_CLOUD_API_KEY", "k",
                        "LLAMA_EXTRACT_BASE_URL", baseUrl,
                        "extract.max_usd_per_document", "0.05"))
                .extractFieldSchema(requestPages(writeOriginal(), List.of(1, 2, 3)),
                        sampleSchema());
        assertThat(out.extractorId()).isEqualTo(LlamaExtractAdapter.DEFAULT_MODEL);
        assertThat(captured.get()).isNotNull();
    }

    @Test
    void costGuardHonorsCreditsUsdRateOverride() {
        // 9 credits × $0.01 (override) = $0.09 > cap $0.05 — refused
        assertThatThrownBy(() -> new LlamaExtractAdapter(Map.of(
                        "LLAMA_CLOUD_API_KEY", "k",
                        "LLAMA_EXTRACT_BASE_URL", baseUrl,
                        "llama.credits_usd_rate", "0.01",
                        "extract.max_usd_per_document", "0.05"))
                .extractFieldSchema(requestPages(writeOriginal(), List.of(1, 2, 3)),
                        sampleSchema()))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("estimated spend");
        assertThat(captured.get()).isNull();
    }

    @Test
    void defaultIdIsLlamaExtract25Agentic() {
        assertThat(new LlamaExtractAdapter(Map.of("LLAMA_CLOUD_API_KEY", "k")).id())
                .isEqualTo("llama-extract-2.5-agentic");
    }

    /** Spy server capturing the extract-create POST body and the uploaded file. */
    private record CreateSpy(HttpServer server, AtomicReference<String> lastCreate,
            AtomicReference<byte[]> lastFile) {}

    private CreateSpy startCreateSpy() throws IOException {
        AtomicReference<String> lastCreate = new AtomicReference<>();
        AtomicReference<byte[]> lastFile = new AtomicReference<>();
        HttpServer spy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        spy.createContext("/api/v1/beta/files", ex -> {
            lastFile.set(ex.getRequestBody().readAllBytes());
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
        return new CreateSpy(spy, lastCreate, lastFile);
    }

    private ExtractRequest requestPages(Path original, List<Integer> pages) {
        return new ExtractRequest(temp, original, pages, "en", "identity.promoter_net_worth",
                "Extract net worth fields", Map.of());
    }

    private static void cite(ObjectNode docMeta, String field, String matchingText, int page) {
        ObjectNode cite = docMeta.putObject(field).putArray("citation").addObject();
        cite.put("page", page);
        cite.put("matching_text", matchingText);
    }

    private static String verbatimOf(ExtractResult out, String key) {
        FactCandidate fact = out.facts().stream()
                .filter(f -> f.key().equals(key)).findFirst().orElseThrow();
        assertThat(fact.citations()).isNotEmpty();
        return fact.citations().get(0).verbatim();
    }

    @Test
    void sendsTargetPagesWhenAPageSubsetIsRequested() throws Exception {
        CreateSpy spy = startCreateSpy();
        try {
            LlamaExtractAdapter a = new LlamaExtractAdapter(Map.of(
                    "LLAMA_CLOUD_API_KEY", "k",
                    "LLAMA_EXTRACT_BASE_URL",
                    "http://127.0.0.1:" + spy.server().getAddress().getPort()));
            a.extractFieldSchema(
                    requestPages(writeOriginal(), List.of(1, 2, 3, 4, 5, 8)), sampleSchema());
            JsonNode configuration = MAPPER.readTree(spy.lastCreate().get()).path("configuration");
            assertThat(configuration.path("target_pages").asText()).isEqualTo("1-5,8");
        } finally {
            spy.server().stop(0);
        }
    }

    @Test
    void omitsTargetPagesWhenAllPagesRequested() throws Exception {
        CreateSpy spy = startCreateSpy();
        try {
            LlamaExtractAdapter a = new LlamaExtractAdapter(Map.of(
                    "LLAMA_CLOUD_API_KEY", "k",
                    "LLAMA_EXTRACT_BASE_URL",
                    "http://127.0.0.1:" + spy.server().getAddress().getPort()));
            a.extractFieldSchema(requestPages(writeOriginal(), List.of()), sampleSchema());
            JsonNode configuration = MAPPER.readTree(spy.lastCreate().get()).path("configuration");
            assertThat(configuration.has("target_pages")).isFalse();
        } finally {
            spy.server().stop(0);
        }
    }

    @Test
    void remainderUploadsTheUnreadLinesAndKeepsTheSchemaPrompt() throws Exception {
        CreateSpy spy = startCreateSpy();
        try {
            LlamaExtractAdapter adapter = new LlamaExtractAdapter(Map.of(
                    "LLAMA_CLOUD_API_KEY", "k",
                    "LLAMA_EXTRACT_BASE_URL",
                    "http://127.0.0.1:" + spy.server().getAddress().getPort()));
            ExtractRequest request = new ExtractRequest(temp, writeOriginal(), List.of(1, 2, 3),
                    "en", "agreement.ppa", "Extract the tariff quote",
                    Map.of(ExtractRequest.REMAINDER_LINES, "Unread tariff line Rs. 3.40",
                            LlamaExtractAdapter.KEY_PARSE_JOB_ID, "job-should-not-be-reused"));
            adapter.extractFieldSchema(request, sampleSchema());
            String uploaded = new String(spy.lastFile().get(), StandardCharsets.UTF_8);
            assertThat(uploaded).contains("Unread tariff line Rs. 3.40");
            assertThat(uploaded).doesNotContain("%PDF");
            JsonNode configuration = MAPPER.readTree(spy.lastCreate().get()).path("configuration");
            assertThat(configuration.path("system_prompt").asText()).isEqualTo("Extract the tariff quote");
            assertThat(configuration.has("target_pages")).isFalse();
        } finally {
            spy.server().stop(0);
        }
    }
}
