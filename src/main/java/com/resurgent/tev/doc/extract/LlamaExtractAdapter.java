package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.residency.DataResidency;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Schema-guided extract against the LlamaParse / LlamaCloud <b>Extract v2</b> REST API.
 * Domain code never sees vendor types; this adapter owns the wire format and maps the
 * reply onto {@link FactCandidate}.
 *
 * <p><b>Wire format</b> (official LlamaExtract v2):
 * <ol>
 *   <li>{@code POST /api/v1/beta/files} multipart {@code file} + {@code purpose=extract}
 *       → {@code id} ({@code dfl-…})</li>
 *   <li>{@code POST /api/v2/extract} JSON {@code {file_input, configuration}}
 *       with {@code data_schema}, {@code tier} (from the layout selector / {@code --tier}),
 *       {@code cite_sources}, {@code confidence_scores}, {@code system_prompt} → job
 *       {@code id} ({@code ext-…})</li>
 *   <li>{@code GET /api/v2/extract/{job_id}?expand=extract_metadata&expand=usage}
 *       until {@code status} is {@code COMPLETED|FAILED|CANCELLED}</li>
 *   <li>{@code extract_result} is the schema-shaped object;
 *       {@code extract_metadata.field_metadata.document_metadata.<field>.citation[]}
 *       carries {@code page}, {@code matching_text}, optional
 *       {@code bounding_boxes} as {@code {x,y,w,h}}</li>
 * </ol>
 *
 * <p>Config (env / {@code .env}, then {@code ExtractRequest.options}):
 * {@code LLAMA_CLOUD_API_KEY} or {@code llamaparse_api_key} (required),
 * optional {@code LLAMA_EXTRACT_BASE_URL}, {@code LLAMA_EXTRACT_MODEL},
 * {@code LLAMA_CLOUD_PROJECT_ID}, {@code extract.timeout_ms} (default 180000),
 * {@code extract.max_usd_per_document}, and the parse tier via the request option
 * {@code llama_parse_tier} (fallback {@code LLAMA_EXTRACT_TIER}, else
 * {@code cost_effective} — never a blanket {@code agentic}).
 */
public final class LlamaExtractAdapter implements ExtractClient {

    /** Default extract tier/model label stored as extractor id when no model override. */
    public static final String DEFAULT_MODEL = "llama-extract-2.5-agentic";

    /** Default LlamaCloud API host. */
    public static final String DEFAULT_BASE_URL = "https://api.cloud.llamaindex.ai";

    /** Config key for per-document HTTP deadline (upload + poll window). */
    public static final String KEY_TIMEOUT_MS = "extract.timeout_ms";

    /** Config key for the optional credit/spend guard. */
    public static final String KEY_MAX_USD = "extract.max_usd_per_document";

    /** USD per LlamaParse credit (vendor list price: 1,000 credits = $1.20). */
    public static final String KEY_CREDITS_USD_RATE = "llama.credits_usd_rate";
    static final double DEFAULT_USD_PER_CREDIT = 0.0012;

    /**
     * Adapter option carrying the Parse tier Extract should run with (or mirror when a parse
     * job is reused). Set by the extract runner from the layout selector or {@code --tier}.
     */
    public static final String KEY_PARSE_TIER = "llama_parse_tier";

    /** Adapter option carrying the reused LlamaParse job id ({@code file_input}). */
    public static final String KEY_PARSE_JOB_ID = "llama_parse_job_id";

    /** Adapter option carrying credits billed for the reused parse job (for {@code llm_usage}). */
    public static final String KEY_PARSE_CREDITS = "llama_parse_credits";

    /** Adapter option carrying the reused parse job's wall-clock latency in ms. */
    public static final String KEY_PARSE_LATENCY_MS = "llama_parse_latency_ms";

    /**
     * Extract tier when neither the request option nor {@code LLAMA_EXTRACT_TIER} is set.
     * Not {@code agentic} on purpose: an untiered document must not silently bill 10–45
     * credits/page. Production passes the selector's tier via {@link #KEY_PARSE_TIER}.
     */
    public static final String DEFAULT_TIER = ParseTier.COST_EFFECTIVE.wireName();

    static final long DEFAULT_TIMEOUT_MS = 180_000L;
    private static final long POLL_INTERVAL_MS = 1_500L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILES_PATH = "/api/v1/beta/files";
    private static final String EXTRACT_PATH = "/api/v2/extract";

    private final String apiKey;
    private final String residency;
    private final String baseUrl;
    private final String model;
    private final String projectId;
    private final long defaultTimeoutMs;
    private final Double defaultMaxUsd;
    private final double usdPerCredit;
    private final String defaultTier;

    /** Live adapter reading LlamaParse/LlamaCloud keys from process env and {@code .env}. */
    public LlamaExtractAdapter() {
        this(loadEnv());
    }

    /**
     * Adapter configured from an env map (tests inject stub host/key without touching
     * the real environment).
     *
     * @param env {@code LLAMA_CLOUD_API_KEY} (or {@code LLAMAPARSE_API_KEY} /
     *     {@code llamaparse_api_key}), optional {@code LLAMA_EXTRACT_BASE_URL} /
     *     {@code LLAMA_EXTRACT_MODEL} / {@code LLAMA_CLOUD_PROJECT_ID} /
     *     {@code llama.credits_usd_rate} /
     *     {@code extract.timeout_ms} / {@code extract.max_usd_per_document}
     */
    public LlamaExtractAdapter(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        this.apiKey = resolveApiKey(env);
        this.residency = env.get(DataResidency.ENV);
        String base = env.get("LLAMA_EXTRACT_BASE_URL");
        this.baseUrl = (base == null || base.isBlank()) ? DEFAULT_BASE_URL : trimSlash(base);
        String m = env.get("LLAMA_EXTRACT_MODEL");
        this.model = (m == null || m.isBlank()) ? DEFAULT_MODEL : m.trim();
        String project = firstNonBlank(env, "LLAMA_CLOUD_PROJECT_ID", "llama_cloud_project_id");
        this.projectId = project == null ? null : project.trim();
        this.defaultTimeoutMs = parseLong(
                firstNonBlank(env, KEY_TIMEOUT_MS, envName(KEY_TIMEOUT_MS)),
                DEFAULT_TIMEOUT_MS);
        this.defaultMaxUsd = parseDouble(
                firstNonBlank(env, KEY_MAX_USD, envName(KEY_MAX_USD)));
        Double rate = parseDouble(
                firstNonBlank(env, KEY_CREDITS_USD_RATE, envName(KEY_CREDITS_USD_RATE)));
        this.usdPerCredit = rate == null || rate <= 0 ? DEFAULT_USD_PER_CREDIT : rate;
        this.defaultTier = resolveDefaultTier(env);
    }

    /** Extract tier floor from {@code LLAMA_EXTRACT_TIER}, else {@link #DEFAULT_TIER}. */
    private static String resolveDefaultTier(Map<String, String> env) {
        String raw = firstNonBlank(env, "LLAMA_EXTRACT_TIER", "llama_extract_tier");
        if (raw == null) {
            return DEFAULT_TIER;
        }
        try {
            return ParseTier.fromWire(raw).wireName();
        } catch (IllegalArgumentException e) {
            return DEFAULT_TIER;
        }
    }

    /**
     * Vendor credit rate as configured in the environment, for callers that report on
     * {@code llm_usage.cost_usd} rows written by this adapter (those hold credits, and their
     * {@code cost_unit} says so).
     */
    public static double usdPerCredit() {
        Map<String, String> env = loadEnv();
        Double rate = parseDouble(
                firstNonBlank(env, KEY_CREDITS_USD_RATE, envName(KEY_CREDITS_USD_RATE)));
        return rate == null || rate <= 0 ? DEFAULT_USD_PER_CREDIT : rate;
    }

    /**
     * Process env plus optional {@code .env} in the working directory (and parents).
     * Process env wins. Keys are kept as written so lowercase {@code llamaparse_api_key} works.
     */
    static Map<String, String> loadEnv() {
        Map<String, String> values = new LinkedHashMap<>(System.getenv());
        for (Path dotenv : new Path[] {
                Path.of(".env"),
                Path.of("..").resolve(".env"),
                Path.of("../..").resolve(".env")}) {
            if (!Files.isRegularFile(dotenv)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(dotenv)) {
                    parseDotenvLine(values, line);
                }
            } catch (IOException ignored) {
                // keep whatever we already have
            }
        }
        return Map.copyOf(values);
    }

    /**
     * Accepts {@code LLAMA_CLOUD_API_KEY}, {@code LLAMAPARSE_API_KEY}, or
     * {@code llamaparse_api_key} (the name used in this repo's {@code .env}).
     */
    static String resolveApiKey(Map<String, String> env) {
        return firstNonBlank(env,
                "LLAMA_CLOUD_API_KEY", "LLAMAPARSE_API_KEY", "llamaparse_api_key",
                "LLAMA_PARSE_API_KEY", "llama_parse_api_key");
    }

    /** True when any accepted LlamaParse/LlamaCloud key name is set. */
    public static boolean hasApiKey() {
        return resolveApiKey(loadEnv()) != null;
    }

    @Override
    public String id() {
        return model;
    }

    @Override
    public ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema)
            throws ExtractException {
        Objects.requireNonNull(req, "req");
        Objects.requireNonNull(schema, "schema");
        String requested = req.options() == null ? null : req.options().get(DataResidency.OPTION);
        if (DataResidency.locked(residency, requested)) {
            throw new ExtractException(
                    "LlamaExtractAdapter blocked: data residency is local_only");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ExtractException(
                    "LlamaParse/LlamaCloud API key is not set; cannot call LlamaExtract"
                            + " (set LLAMA_CLOUD_API_KEY or llamaparse_api_key in .env,"
                            + " or choose another extractor)");
        }
        String remainder = req.options() == null ? null
                : req.options().get(ExtractRequest.REMAINDER_LINES);
        boolean remainderOnly = remainder != null && !remainder.isBlank();
        Path remainderFile = null;
        long timeoutMs = resolveLong(req.options(), KEY_TIMEOUT_MS, defaultTimeoutMs);
        Double maxUsd = resolveDouble(req.options(), KEY_MAX_USD, defaultMaxUsd);
        long startNanos = System.nanoTime();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 30_000L)))
                .build();

        try {
            if (remainderOnly) {
                remainderFile = Files.createTempFile("tev-remainder-", ".txt");
                Files.writeString(remainderFile, remainder, StandardCharsets.UTF_8);
            }
            Path original = remainderOnly ? remainderFile : req.originalFile();
            String parseJobId = req.options() != null ? req.options().get(KEY_PARSE_JOB_ID) : null;
            boolean hasParseJob = !remainderOnly && parseJobId != null && !parseJobId.isBlank();
            List<Integer> pageIndices = remainderOnly ? List.of() : req.pageIndices();
            if (!hasParseJob && (original == null || !Files.isRegularFile(original))) {
                throw new ExtractException("originalFile is required for LlamaExtract: " + original
                        + " (or set options.llama_parse_job_id)");
            }
            // Cost guard before/around the call (S2.3): estimate the expected spend from
            // the selected page count and the tier's credits-per-page rate, convert the
            // credits to USD at the vendor rate, and refuse pre-submit when the estimate
            // exceeds the USD cap — no spend is incurred.
            String tierWire = resolveTier(req.options());
            int pages = remainderOnly ? 1 : estimatePageCount(req);
            int creditsPerPage = ParseTier.fromWire(tierWire).creditsPerPage();
            double estimatedCredits = (double) pages * creditsPerPage;
            double estimatedUsd = estimatedCredits * usdPerCredit;
            Double ceiling = resolveDouble(req.options(),
                    com.resurgent.tev.doc.platform.SpendCeiling.CEILING_OPTION, null);
            Double spent = resolveDouble(req.options(),
                    com.resurgent.tev.doc.platform.SpendCeiling.SPENT_OPTION, null);
            if (ceiling != null && !com.resurgent.tev.doc.platform.SpendCeiling.allows(
                    ceiling, spent == null ? 0 : spent, estimatedUsd)) {
                throw new ExtractException(
                        "LlamaExtractAdapter blocked: spend ceiling");
            }
            if (maxUsd != null && estimatedUsd > maxUsd) {
                throw new ExtractException("LlamaExtract estimated spend " + estimatedCredits
                        + " credits (" + pages + " pages × " + creditsPerPage
                        + " credits/page on tier " + tierWire + ") ≈ $" + estimatedUsd
                        + " exceeds extract.max_usd_per_document " + maxUsd
                        + "; refusing to submit");
            }
            // Prefer an existing LlamaParse job id (official file_input); else upload bytes.
            String fileId = hasParseJob ? parseJobId.trim() : null;
            if (fileId == null) {
                fileId = uploadFile(client, original, timeoutMs);
            }
            String jobId = createExtractJob(client, fileId, schema, req.instructions(),
                    tierWire, PageSelection.wire(pageIndices), timeoutMs);
            JsonNode job = pollJob(client, jobId, timeoutMs, startNanos);
            String status = job.path("status").asText("");
            if (!"COMPLETED".equals(status)) {
                String message = job.path("error_message").asText(null);
                throw new ExtractException("LlamaExtract job " + jobId + " ended " + status
                        + (message == null || message.isBlank() ? "" : ": " + message));
            }

            JsonNode extractResult = job.path("extract_result");
            if (extractResult.isMissingNode() || extractResult.isNull()) {
                throw new ExtractException("LlamaExtract job " + jobId + " has no extract_result");
            }
            JsonNode fieldMeta = job.path("extract_metadata")
                    .path("field_metadata")
                    .path("document_metadata");
            Mapping mapping = mapResult(extractResult, fieldMeta, schema);
            ExtractStats stats = statsFrom(job, startNanos, hasParseJob);
            Double spentCredits = stats.credits();
            if (maxUsd != null && spentCredits != null
                    && spentCredits * usdPerCredit > maxUsd) {
                throw new ExtractException("LlamaExtract usage " + spentCredits
                        + " credits ≈ $" + (spentCredits * usdPerCredit)
                        + " exceeds extract.max_usd_per_document " + maxUsd);
            }
            return new ExtractResult(model, job, mapping.facts(), mapping.citations(), stats);
        } catch (HttpTimeoutException e) {
            throw new ExtractException("LlamaExtract timed out after " + timeoutMs + " ms", e);
        } catch (ExtractException e) {
            throw e;
        } catch (IOException e) {
            throw new ExtractException("LlamaExtract I/O failure: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractException("LlamaExtract interrupted", e);
        } finally {
            if (remainderFile != null) {
                try {
                    Files.deleteIfExists(remainderFile);
                } catch (IOException ignored) {
                    // the unread-line file is temporary
                }
            }
        }
    }

    // ---- HTTP ---------------------------------------------------------------

    private String uploadFile(HttpClient client, Path original, long timeoutMs)
            throws IOException, InterruptedException, ExtractException {
        String boundary = "----tev" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipartFile(boundary, original);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + FILES_PATH))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        JsonNode json = send(client, request, "file upload");
        String id = json.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new ExtractException("LlamaExtract file upload returned no id");
        }
        return id;
    }

    private String createExtractJob(HttpClient client, String fileId, JsonNode schema,
            String instructions, String tierWire, String targetPages, long timeoutMs)
            throws IOException, InterruptedException, ExtractException {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("file_input", fileId);
        ObjectNode configuration = payload.putObject("configuration");
        configuration.put("tier", tierWire);
        configuration.put("version", "2.5");
        configuration.put("extraction_target", "per_doc");
        configuration.set("data_schema", schema);
        configuration.put("cite_sources", true);
        configuration.put("confidence_scores", true);
        if (targetPages != null && !targetPages.isBlank()) {
            // 1-based page selection (--pages); pages are processed in the order listed
            configuration.put("target_pages", targetPages);
        }
        if (instructions != null && !instructions.isBlank()) {
            configuration.put("system_prompt", instructions.trim());
        }
        if (model != null && !model.isBlank() && !DEFAULT_MODEL.equals(model)) {
            // optional model override when a concrete LlamaExtract model id is configured
            configuration.put("model", model);
        }

        HttpRequest request = HttpRequest.newBuilder(jobUri(""))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        JsonNode json = send(client, request, "extract create");
        String id = json.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new ExtractException("LlamaExtract create returned no job id");
        }
        return id;
    }

    private JsonNode pollJob(HttpClient client, String jobId, long timeoutMs, long startNanos)
            throws IOException, InterruptedException, ExtractException {
        long deadline = startNanos + timeoutMs * 1_000_000L;
        while (true) {
            HttpRequest request = HttpRequest.newBuilder(
                            jobUri("/" + jobId + "?expand=extract_metadata&expand=usage"))
                    .timeout(Duration.ofMillis(Math.max(5_000L, remainingMs(deadline))))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("accept", "application/json")
                    .GET()
                    .build();
            JsonNode job = send(client, request, "extract get");
            String status = job.path("status").asText("");
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                return job;
            }
            if (System.nanoTime() >= deadline) {
                throw new ExtractException("LlamaExtract job " + jobId
                        + " still " + status + " after " + timeoutMs + " ms");
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
    }

    private URI jobUri(String suffix) {
        StringBuilder sb = new StringBuilder(baseUrl).append(EXTRACT_PATH);
        if (suffix != null && !suffix.isBlank()) {
            sb.append(suffix);
        }
        if (projectId != null && !projectId.isBlank()) {
            sb.append(suffix != null && suffix.contains("?") ? "&" : "?")
                    .append("project_id=").append(projectId);
        }
        return URI.create(sb.toString());
    }

    private static long remainingMs(long deadlineNanos) {
        long left = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        return Math.max(1_000L, left);
    }

    private JsonNode send(HttpClient client, HttpRequest request, String what)
            throws IOException, InterruptedException, ExtractException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (code < 200 || code >= 300) {
            throw new ExtractException("LlamaExtract " + what + " HTTP " + code
                    + truncate(body, 300));
        }
        try {
            return MAPPER.readTree(body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            throw new ExtractException("LlamaExtract " + what + " returned malformed JSON"
                    + truncate(body, 200), e);
        }
    }

    private static byte[] multipartFile(String boundary, Path file) throws IOException {
        String name = file.getFileName() == null ? "document" : file.getFileName().toString();
        byte[] bytes = Files.readAllBytes(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String crlf = "\r\n";
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + name.replace("\"", "") + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: application/octet-stream" + crlf + crlf)
                .getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"purpose\"" + crlf + crlf)
                .getBytes(StandardCharsets.UTF_8));
        out.write("extract".getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // ---- mapping ------------------------------------------------------------

    private record Mapping(List<FactCandidate> facts, List<Citation> citations) {}

    private static Mapping mapResult(JsonNode extractResult, JsonNode fieldMeta, JsonNode schema) {
        List<FactCandidate> facts = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        JsonNode props = schema.path("properties");

        if (extractResult.isArray()) {
            // root array (unusual for packs) — treat each element as a line-item style fact
            int i = 0;
            for (JsonNode row : extractResult) {
                FactCandidate candidate = new FactCandidate("line_items", ValueKind.TEXT)
                        .valueText(row.isValueNode() ? row.asText() : row.toString())
                        .lineKey("row-" + (++i))
                        .confidence(null)
                        .extractionMethod("llm_field")
                        .statedOrDerived("stated");
                facts.add(candidate);
            }
            return new Mapping(List.copyOf(facts), List.copyOf(citations));
        }

        IteratorLike it = new IteratorLike(extractResult);
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            JsonNode meta = fieldMeta.path(key);
            ValueKind kind = kindFor(props, key);
            if (value.isArray() && "line_items".equals(key)) {
                mapLineItems(value, meta, facts, citations);
                continue;
            }
            if (value.isArray() && ScheduleCells.isSchedule(props.path(key).path("items")
                    .path("properties"))) {
                JsonNode itemProps = props.path(key).path("items").path("properties");
                for (JsonNode row : value) {
                    if (row.isObject()) {
                        ScheduleCells.expand(key, itemProps, row, facts, citations);
                    }
                }
                continue;
            }
            if (value.isArray()) {
                int index = 0;
                for (JsonNode element : value) {
                    JsonNode elementMeta = meta.isArray() && index < meta.size()
                            ? meta.get(index) : meta;
                    mapScalar(key, kind, element, elementMeta, facts, citations,
                            String.valueOf(index));
                    index++;
                }
                continue;
            }
            mapScalar(key, kind, value, meta, facts, citations, null);
        }
        return new Mapping(List.copyOf(facts), List.copyOf(citations));
    }

    private static void mapLineItems(JsonNode array, JsonNode meta,
            List<FactCandidate> facts, List<Citation> citations) {
        int index = 0;
        for (JsonNode row : array) {
            String description = row.path("description").asText(
                    row.path("item").asText(null));
            FactCandidate candidate = new FactCandidate("line_items", ValueKind.TEXT)
                    .valueText(description)
                    .lineKey(description)
                    .extractionMethod("llm_field")
                    .statedOrDerived("stated");
            if (row.has("amount")) {
                candidate.valueNumber(row.path("amount").asDouble());
            } else if (row.has("qty") || row.has("quantity")) {
                candidate.valueNumber(row.path("qty").asDouble(
                        row.path("quantity").asDouble()));
            }
            JsonNode rowMeta = meta.isArray() && index < meta.size() ? meta.get(index) : meta;
            addCitations(rowMeta, candidate, citations);
            facts.add(candidate);
            index++;
        }
    }

    private static void mapScalar(String key, ValueKind kind, JsonNode value, JsonNode meta,
            List<FactCandidate> facts, List<Citation> citations, String indexSuffix) {
        if (value == null || value.isNull()) {
            return;
        }
        FactCandidate candidate = new FactCandidate(key, kind)
                .extractionMethod("llm_field")
                .statedOrDerived("stated");
        Double confidence = meta.hasNonNull("confidence")
                ? meta.get("confidence").asDouble() : null;
        candidate.confidence(confidence);

        if (value.isNumber()) {
            candidate.valueNumber(value.asDouble());
            // plain figure text: Double.toString turns large amounts into
            // scientific notation, which money parsing and quote matching lose
            candidate.valueText(value.decimalValue().stripTrailingZeros().toPlainString());
        } else if (value.isBoolean()) {
            candidate.valueText(value.asBoolean() ? "true" : "false");
        } else {
            String text = value.asText();
            candidate.valueText(text);
            if (kind == ValueKind.MONEY || kind == ValueKind.QUANTITY
                    || kind == ValueKind.RATE || kind == ValueKind.PERCENT
                    || kind == ValueKind.DURATION) {
                try {
                    candidate.valueNumber(Double.parseDouble(text.replace(",", "")));
                } catch (NumberFormatException ignored) {
                    // keep text only
                }
            }
            if (kind == ValueKind.DATE) {
                candidate.valueDate(text);
            }
        }
        if (meta.hasNonNull("unit")) {
            candidate.unit(meta.get("unit").asText());
        }
        if (meta.hasNonNull("currency")) {
            candidate.currency(meta.get("currency").asText());
        }
        if (meta.hasNonNull("scale")) {
            candidate.scale(meta.get("scale").asText());
        }
        if (indexSuffix != null) {
            candidate.lineKey(key + "[" + indexSuffix + "]");
        }
        addCitations(meta, candidate, citations);
        facts.add(candidate);
    }

    private static void addCitations(JsonNode meta, FactCandidate candidate,
            List<Citation> citations) {
        JsonNode citationArray = meta.path("citation");
        if (!citationArray.isArray()) {
            return;
        }
        for (JsonNode c : citationArray) {
            int page = c.path("page").asInt(1);
            String quote = c.path("matching_text").asText(null);
            if (quote == null || quote.isBlank()) {
                quote = candidate.valueText();
            }
            if (quote == null || quote.isBlank()) {
                continue;
            }
            Citation citation = null;
            JsonNode boxes = c.path("bounding_boxes");
            if (boxes.isArray() && boxes.size() > 0) {
                JsonNode box = boxes.get(0);
                double x = box.path("x").asDouble(0);
                double y = box.path("y").asDouble(0);
                double w = box.path("w").asDouble(0);
                double h = box.path("h").asDouble(0);
                citation = Citation.of(page, x, y, x + w, y + h, quote);
            } else {
                citation = Citation.of(page, quote);
            }
            candidate.addCitation(citation);
            citations.add(citation);
        }
    }

    private static ValueKind kindFor(JsonNode props, String key) {
        JsonNode leaf = props != null ? props.path(key) : null;
        if (leaf != null && leaf.isObject()) {
            String kind = leaf.path("value_kind").asText(null);
            if (kind != null) {
                try {
                    return ValueKind.fromWire(kind);
                } catch (IllegalArgumentException ignored) {
                    // fall through
                }
            }
        }
        return ValueKind.TEXT;
    }

    /**
     * Extract tier for the request: the parse tier mapped onto what LlamaExtract accepts,
     * else the configured default. Never a blanket agentic.
     *
     * <p>LlamaParse's cheapest tier, {@code fast}, has no LlamaExtract equivalent — sending
     * it in {@code configuration.tier} is rejected with HTTP 422 — so a fast parse extracts
     * at {@code cost_effective}, the next tier up that both services accept.
     */
    private String resolveTier(Map<String, String> options) {
        String raw = options != null ? options.get(KEY_PARSE_TIER) : null;
        if (raw == null || raw.isBlank()) {
            return defaultTier;
        }
        try {
            ParseTier parsed = ParseTier.fromWire(raw);
            return parsed == ParseTier.FAST
                    ? ParseTier.COST_EFFECTIVE.wireName()
                    : parsed.wireName();
        } catch (IllegalArgumentException e) {
            return defaultTier;
        }
    }

    /**
     * Page count for the pre-submit spend estimate: the explicit {@code --pages}
     * selection when given, else the laid-out page PNGs in {@code pageBundleDir/pages/},
     * else 1 (unknown — the post-call usage verification still guards the actual spend).
     */
    private static int estimatePageCount(ExtractRequest req) {
        if (req.pageIndices() != null && !req.pageIndices().isEmpty()) {
            return req.pageIndices().size();
        }
        if (req.pageBundleDir() != null) {
            Path pages = req.pageBundleDir().resolve("pages");
            if (Files.isDirectory(pages)) {
                try (var stream = Files.list(pages)) {
                    long count = stream.filter(Files::isRegularFile).count();
                    if (count > 0) {
                        return (int) count;
                    }
                } catch (IOException ignored) {
                    // fall through to the conservative default
                }
            }
        }
        return 1;
    }

    /**
     * Extract spend for {@code llm_usage}. When a parse job backed this extract ({@code hasParseJob}),
     * its {@code parse_credits} are recorded separately against the parse call, so only
     * {@code extract_credits} are attributed here; otherwise the extract uploaded and parsed
     * internally, so the total {@code credits} are this call's spend.
     *
     * @param hasParseJob true when an existing LlamaParse job supplied {@code file_input}
     */
    private static ExtractStats statsFrom(JsonNode job, long startNanos, boolean hasParseJob) {
        long latency = (System.nanoTime() - startNanos) / 1_000_000L;
        JsonNode usage = job.path("usage");
        if (!usage.isObject()) {
            return ExtractStats.local(latency);
        }
        Double credits;
        if (hasParseJob && usage.hasNonNull("extract_credits")) {
            credits = usage.get("extract_credits").asDouble();
        } else if (usage.hasNonNull("credits")) {
            credits = usage.get("credits").asDouble();
        } else if (usage.hasNonNull("extract_credits")) {
            credits = usage.get("extract_credits").asDouble();
        } else {
            credits = null;
        }
        // LlamaCloud bills credits, not USD; the credit total is stored in the llm_usage
        // cost slot for attribution, and the cost guard converts it via usdPerCredit.
        // ExtractService records that slot as CostUnit.CREDITS so a reader can tell (#152).
        return new ExtractStats(1, 0, 0, 0, credits, latency);
    }

    // ---- small helpers ------------------------------------------------------

    /** Minimal field iterator so we do not depend on Iterator generics in tests. */
    private static final class IteratorLike {
        private final List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        private int index;

        IteratorLike(JsonNode objectNode) {
            objectNode.fields().forEachRemaining(entries::add);
        }

        boolean hasNext() {
            return index < entries.size();
        }

        Map.Entry<String, JsonNode> next() {
            return entries.get(index++);
        }
    }

    private static String firstNonBlank(Map<String, String> env, String... names) {
        for (String name : names) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static long resolveLong(Map<String, String> options, String key, long fallback) {
        String raw = options != null ? options.get(key) : null;
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty(key);
        }
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(key);
        }
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(envName(key));
        }
        return parseLong(raw, fallback);
    }

    private static Double resolveDouble(Map<String, String> options, String key, Double fallback) {
        String raw = options != null ? options.get(key) : null;
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty(key);
        }
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(key);
        }
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(envName(key));
        }
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return parseDouble(raw);
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String envName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    private static String trimSlash(String base) {
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String truncate(String body, int max) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String b = body.trim();
        return ": " + (b.length() <= max ? b : b.substring(0, max) + "…");
    }

    private static void parseDotenvLine(Map<String, String> values, String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
            return;
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String name = trimmed.substring(0, eq).trim();
        String value = unquote(trimmed.substring(eq + 1).trim());
        values.putIfAbsent(name, value);
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
