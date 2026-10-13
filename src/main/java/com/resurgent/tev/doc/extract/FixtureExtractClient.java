package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline deterministic {@link ExtractClient} for unit tests and golden fixtures.
 * Reads a JSON sidecar next to the document (or a classpath fixture keyed by
 * {@code doc_type_code}) mapping field keys to canned values + quotes.
 *
 * <p>Never selected when {@code LLAMA_CLOUD_API_KEY} is set unless {@code --extractor fixture}
 * is explicit (routing lives in the extract service/CLI).
 */
public final class FixtureExtractClient implements ExtractClient {

    /** Adapter id stored on {@code fact.extractor_id}. */
    public static final String ID = "fixture-extract";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, JsonNode> byDocType = new LinkedHashMap<>();

    /** Creates an empty fixture client; call {@link #loadSidecar} or {@link #putFixture}. */
    public FixtureExtractClient() {}

    /**
     * Loads a JSON sidecar file (golden {@code *.expected.json}).
     *
     * @param path sidecar path (required)
     * @throws IOException when the file cannot be read
     */
    public void loadSidecar(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            JsonNode root = MAPPER.readTree(in);
            String code = root.path("doc_type_code").asText("unknown");
            byDocType.put(code, root);
        }
    }

    /**
     * Registers an in-memory fixture for a doc type code.
     *
     * @param docTypeCode pack code (required)
     * @param fixture fixture JSON with fields/line_items (required)
     */
    public void putFixture(String docTypeCode, JsonNode fixture) {
        byDocType.put(docTypeCode, fixture);
    }

    /**
     * Loads every {@code *.expected.json} under a directory into this client.
     *
     * @param dir fixture directory (required)
     * @throws IOException when a sidecar cannot be read
     */
    public void loadDirectory(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".expected.json"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                loadSidecar(file);
            }
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema) {
        long start = System.nanoTime();
        String code = req.docTypeCode() != null ? req.docTypeCode() : "unknown";
        JsonNode fixture = byDocType.get(code);
        if (fixture == null && byDocType.size() == 1) {
            fixture = byDocType.values().iterator().next();
        }
        List<FactCandidate> facts = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("fixture", true);
        payload.put("doc_type_code", code);

        if (fixture != null) {
            JsonNode fields = fixture.path("fields");
            if (fields.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = fields.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    JsonNode cell = e.getValue();
                    String value = cell.hasNonNull("value") ? cell.get("value").asText() : null;
                    String quote = cell.hasNonNull("quote") ? cell.get("quote").asText() : value;
                    int page = cell.hasNonNull("page") ? cell.get("page").asInt(1) : 1;
                    ValueKind kind = kindFor(schema, e.getKey());
                    FactCandidate candidate = new FactCandidate(e.getKey(), kind)
                            .valueText(value)
                            .confidence(0.95)
                            .extractionMethod("deterministic")
                            .statedOrDerived("stated");
                    if (quote != null) {
                        Citation citation = Citation.of(page, quote);
                        candidate.addCitation(citation);
                        citations.add(citation);
                    }
                    facts.add(candidate);
                }
            }
            JsonNode lineItems = fixture.path("line_items");
            if (lineItems.isArray()) {
                for (JsonNode line : lineItems) {
                    String description = line.path("description").asText(null);
                    FactCandidate row = new FactCandidate("line_items", ValueKind.TEXT)
                            .valueText(description)
                            .lineKey(description)
                            .subjectEntity(fields.path("vendor_name").path("value").asText(null))
                            .confidence(0.9)
                            .extractionMethod("deterministic")
                            .statedOrDerived("stated");
                    if (line.hasNonNull("amount")) {
                        row.valueNumber(line.get("amount").asDouble());
                    }
                    if (line.hasNonNull("qty")) {
                        row.unit("qty:" + line.get("qty").asText());
                    }
                    String quote = line.path("quote").asText(description);
                    int page = line.hasNonNull("page") ? line.get("page").asInt(1) : 1;
                    if (quote != null) {
                        Citation citation = Citation.of(page, quote);
                        row.addCitation(citation);
                        citations.add(citation);
                    }
                    facts.add(row);
                }
            }
        }

        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return new ExtractResult(ID, payload, keepOnPages(facts, req.pageIndices()), citations,
                ExtractStats.local(latencyMs));
    }

    /**
     * Simulates {@code target_pages} offline: when a page subset is requested, drop facts
     * whose citations all fall outside it. Facts with no citation are kept (nothing to
     * disprove their page).
     */
    private static List<FactCandidate> keepOnPages(List<FactCandidate> facts,
            List<Integer> pages) {
        if (pages == null || pages.isEmpty()) {
            return facts;
        }
        List<FactCandidate> kept = new ArrayList<>();
        for (FactCandidate fact : facts) {
            if (fact.citations().isEmpty()
                    || fact.citations().stream().anyMatch(c -> pages.contains(c.pageIndex()))) {
                kept.add(fact);
            }
        }
        return kept;
    }

    private static ValueKind kindFor(JsonNode schema, String key) {
        JsonNode props = schema != null ? schema.path("properties") : null;
        if (props != null && props.has(key)) {
            String kind = props.path(key).path("value_kind").asText(null);
            if (kind != null) {
                try {
                    return ValueKind.fromWire(kind);
                } catch (IllegalArgumentException ignored) {
                    // fall through to text
                }
            }
        }
        return ValueKind.TEXT;
    }

    /**
     * Builds a tiny schema stub so fixtures work without a full pack.
     *
     * @param keys field keys to declare as text
     * @return JSON Schema fragment
     */
    public static JsonNode textSchema(String... keys) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ArrayNode required = root.putArray("required");
        for (String key : keys) {
            ObjectNode leaf = props.putObject(key);
            leaf.put("type", "string");
            leaf.put("value_kind", "text");
            required.add(key);
        }
        return root;
    }
}
