package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Extract mapping infers {@code value_kind} from JSON Schema {@code type} when a
 * leaf omits pack {@code value_kind} (issue #198). Money stays opt-in via schema
 * cues, never field-name or response-meta heuristics.
 */
class InferredValueKindExtractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    private HttpServer server;
    private String baseUrl;
    private String getResponse = "{\"id\":\"ext-test\",\"status\":\"COMPLETED\",\"extract_result\":{}}";

    @BeforeEach
    void startServer() throws IOException {
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
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void numberLeafWithoutValueKindMapsToQuantityAndNormalizesUnitScale() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode capacity = ((ObjectNode) schema.get("properties")).putObject("capacity");
        capacity.put("type", "number");

        ExtractResult out = extract(schema, result -> result.put("capacity", 1842.5));
        FactCandidate fact = fact(out, "capacity");
        assertThat(fact.valueKind()).isEqualTo(ValueKind.QUANTITY);
        assertThat(fact.valueNumber()).isEqualTo(1842.5);

        CandidateNormalizer.normalize(fact);
        assertThat(fact.scale()).isEqualTo("unit");
        assertThat(fact.unit()).isNull();
        assertThat(fact.currency()).isNull();
    }

    @Test
    void booleanLeafWithoutValueKindMapsToBoolean() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode flag = ((ObjectNode) schema.get("properties")).putObject("grid_connected");
        flag.put("type", "boolean");

        ExtractResult out = extract(schema, result -> result.put("grid_connected", true));
        FactCandidate fact = fact(out, "grid_connected");
        assertThat(fact.valueKind()).isEqualTo(ValueKind.BOOLEAN);
        assertThat(fact.valueText()).isEqualTo("true");
    }

    @Test
    void stringDateFormatWithoutValueKindMapsToDateAndNormalizesIso() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode asOf = ((ObjectNode) schema.get("properties")).putObject("as_of_date");
        asOf.put("type", "string");
        asOf.put("format", "date");

        ExtractResult out = extract(schema, result -> result.put("as_of_date", "15/03/2024"));
        FactCandidate fact = fact(out, "as_of_date");
        assertThat(fact.valueKind()).isEqualTo(ValueKind.DATE);

        CandidateNormalizer.normalize(fact);
        assertThat(fact.valueDate()).isEqualTo("2024-03-15");
    }

    @Test
    void stringLeafWithoutValueKindStaysText() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode name = ((ObjectNode) schema.get("properties")).putObject("subject_name");
        name.put("type", "string");

        ExtractResult out = extract(schema, result -> result.put("subject_name", "Ramesh Kumar"));
        FactCandidate fact = fact(out, "subject_name");
        assertThat(fact.valueKind()).isEqualTo(ValueKind.TEXT);
        assertThat(fact.valueText()).isEqualTo("Ramesh Kumar");
    }

    @Test
    void currencyFlagOnANumberIsMoneyAndNormalizesTheFigure() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode amount = ((ObjectNode) schema.get("properties")).putObject("net_worth");
        amount.put("type", "number");
        amount.put("currency", true);

        ExtractResult out = extract(schema, result -> result.put("net_worth", 16000000));
        FactCandidate fact = fact(out, "net_worth");
        assertThat(fact.valueKind()).isEqualTo(ValueKind.MONEY);
        assertThat(fact.valueNumber()).isEqualTo(16000000.0);

        CandidateNormalizer.normalize(fact, new DocMoneyContext("INR", null));
        assertThat(fact.currency()).isEqualTo("INR");
        assertThat(fact.scale()).isNull();
    }

    @Test
    void scaleFlagOnANumberIsMoney() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode amount = ((ObjectNode) schema.get("properties")).putObject("consideration");
        amount.put("type", "number");
        amount.put("scale", true);

        ExtractResult out = extract(schema, result -> result.put("consideration", 10));
        assertThat(fact(out, "consideration").valueKind()).isEqualTo(ValueKind.MONEY);
    }

    @Test
    void moneyMarkerOnANumberIsMoney() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode amount = ((ObjectNode) schema.get("properties")).putObject("fee");
        amount.put("type", "number");
        amount.put("money", true);

        ExtractResult out = extract(schema, result -> result.put("fee", 5000));
        assertThat(fact(out, "fee").valueKind()).isEqualTo(ValueKind.MONEY);
    }

    @Test
    void englishFieldNameDoesNotMakeANumberMoney() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode amount = ((ObjectNode) schema.get("properties")).putObject("net_worth");
        amount.put("type", "number");

        ExtractResult out = extract(schema, result -> result.put("net_worth", 2500000));
        FactCandidate fact = fact(out, "net_worth");
        assertThat(fact.valueKind()).isEqualTo(ValueKind.QUANTITY);
        assertThat(fact.valueKind()).isNotEqualTo(ValueKind.MONEY);
    }

    @Test
    void interestRateNameDoesNotInferPercentOrRate() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode rate = ((ObjectNode) schema.get("properties")).putObject("interest_rate");
        rate.put("type", "number");

        ExtractResult out = extract(schema, result -> result.put("interest_rate", 12.5));
        assertThat(fact(out, "interest_rate").valueKind()).isEqualTo(ValueKind.QUANTITY);
    }

    @Test
    void explicitPackValueKindWinsOverJsonType() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode yield = ((ObjectNode) schema.get("properties")).putObject("specific_yield");
        yield.put("type", "number");
        yield.put("value_kind", "quantity");
        ObjectNode worth = ((ObjectNode) schema.get("properties")).putObject("net_worth");
        worth.put("type", "number");
        worth.put("value_kind", "money");
        ObjectNode iou = ((ObjectNode) schema.get("properties")).putObject("iou_rate");
        iou.put("type", "number");
        iou.put("value_kind", "percent");

        ExtractResult out = extract(schema, result -> {
            result.put("specific_yield", 1842.5);
            result.put("net_worth", 2500000);
            result.put("iou_rate", 12.5);
        });
        assertThat(fact(out, "specific_yield").valueKind()).isEqualTo(ValueKind.QUANTITY);
        assertThat(fact(out, "net_worth").valueKind()).isEqualTo(ValueKind.MONEY);
        assertThat(fact(out, "iou_rate").valueKind()).isEqualTo(ValueKind.PERCENT);
    }

    @Test
    void fixtureClientInfersQuantityFromRawNumberSchema() throws Exception {
        ObjectNode schema = objectSchema();
        ((ObjectNode) schema.get("properties")).putObject("capacity").put("type", "number");
        FixtureExtractClient client = new FixtureExtractClient();
        client.putFixture("demo.inferred", MAPPER.readTree("""
                {"fields":{"capacity":{"value":"4.5 acre","quote":"4.5 acre","page":1}}}
                """));
        Path pdf = temp.resolve("fixture.pdf");
        Files.writeString(pdf, "%PDF-1.4 test");
        ExtractResult out = client.extractFieldSchema(new ExtractRequest(
                temp, pdf, List.of(1), "en", "demo.inferred", "Extract", Map.of()), schema);
        FactCandidate fact = fact(out, "capacity");
        assertThat(fact.valueKind()).isEqualTo(ValueKind.QUANTITY);
        CandidateNormalizer.normalize(fact);
        assertThat(fact.valueNumber()).isEqualTo(4.5);
        assertThat(fact.unit()).isEqualTo("acre");
        assertThat(fact.scale()).isEqualTo("unit");
    }

    @Test
    void unitFlagAloneDoesNotMakeANumberMoney() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode capacity = ((ObjectNode) schema.get("properties")).putObject("capacity");
        capacity.put("type", "number");
        capacity.put("unit", true);

        ExtractResult out = extract(schema, result -> result.put("capacity", 4.5));
        assertThat(fact(out, "capacity").valueKind()).isEqualTo(ValueKind.QUANTITY);
    }

    @Test
    void llamaExtractResponseMetaCurrencyDoesNotMakeANumberMoney() throws Exception {
        ObjectNode schema = objectSchema();
        ObjectNode amount = ((ObjectNode) schema.get("properties")).putObject("capacity");
        amount.put("type", "number");

        ObjectNode job = MAPPER.createObjectNode();
        job.put("status", "COMPLETED");
        job.putObject("extract_result").put("capacity", 4.5);
        ObjectNode meta = job.putObject("extract_metadata")
                .putObject("field_metadata")
                .putObject("document_metadata")
                .putObject("capacity");
        meta.put("currency", "INR");
        meta.put("scale", "lakh");
        getResponse = job.toString();

        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4 test");
        LlamaExtractAdapter adapter = new LlamaExtractAdapter(Map.of(
                "LLAMA_CLOUD_API_KEY", "test-key",
                "LLAMA_EXTRACT_BASE_URL", baseUrl));
        ExtractResult out = adapter.extractFieldSchema(new ExtractRequest(
                temp, pdf, List.of(1), "en", "demo.inferred",
                "Extract fields", Map.of()), schema);
        FactCandidate fact = fact(out, "capacity");
        assertThat(fact.valueKind()).isEqualTo(ValueKind.QUANTITY);
        assertThat(fact.currency()).isEqualTo("INR");
    }

    private ExtractResult extract(JsonNode schema, java.util.function.Consumer<ObjectNode> result)
            throws Exception {
        ObjectNode job = MAPPER.createObjectNode();
        job.put("status", "COMPLETED");
        ObjectNode extractResult = job.putObject("extract_result");
        result.accept(extractResult);
        getResponse = job.toString();
        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4 test");
        LlamaExtractAdapter adapter = new LlamaExtractAdapter(Map.of(
                "LLAMA_CLOUD_API_KEY", "test-key",
                "LLAMA_EXTRACT_BASE_URL", baseUrl));
        return adapter.extractFieldSchema(new ExtractRequest(
                temp, pdf, List.of(1), "en", "demo.inferred",
                "Extract fields", Map.of()), schema);
    }

    private static ObjectNode objectSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    private static FactCandidate fact(ExtractResult out, String key) {
        return out.facts().stream()
                .filter(f -> key.equals(f.key()))
                .findFirst()
                .orElseThrow();
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
