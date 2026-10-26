package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.residency.DataResidency;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Thin client for LlamaParse / LlamaCloud <b>Parse v2</b> (agentic OCR).
 *
 * <p>Wire format (official REST):
 * <ul>
 *   <li>{@code POST /api/v2/parse/upload} multipart {@code file} + {@code configuration}
 *       JSON → job {@code id} at the top level ({@code pjb-…})</li>
 *   <li>{@code GET /api/v2/parse/{job_id}?expand=text,markdown,usage} →
 *       status under {@code job.status}; pages under {@code job.markdown.pages} /
 *       {@code job.text.pages}</li>
 * </ul>
 *
 * <p>Parse job ids can be passed as Extract v2 {@code file_input} so extract skips
 * re-parsing. Env keys match {@link LlamaExtractAdapter}.
 */
public final class LlamaParseClient implements PageParseClient {

    /**
     * Fallback Parse tier when no caller tier is configured. Production always passes an
     * explicit tier (the {@link ParseTierSelector} result or {@code --tier}); this floor is
     * deliberately the cheap {@code cost_effective} tier rather than {@code agentic}, so a
     * caller that forgets to tier a document does not silently bill 10–45 credits/page.
     */
    public static final String DEFAULT_TIER = ParseTier.COST_EFFECTIVE.wireName();

    /** Default Parse version pin when none is configured. */
    public static final String DEFAULT_VERSION = "latest";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PARSE_PATH = "/api/v2/parse";
    private static final long POLL_INTERVAL_MS = 1_500L;

    private final String apiKey;
    private final String baseUrl;
    private final String tier;
    private final String version;
    private final long timeoutMs;
    private final String residency;

    /** Live client from process env and {@code .env}. */
    public LlamaParseClient() {
        this(LlamaExtractAdapter.loadEnv());
    }

    /**
     * Client from an env map (tests inject stub host/key).
     *
     * @param env LlamaParse key aliases plus optional {@code LLAMA_EXTRACT_BASE_URL},
     *     {@code LLAMA_PARSE_TIER}, {@code LLAMA_PARSE_VERSION}, {@code extract.timeout_ms}
     */
    public LlamaParseClient(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        this.apiKey = LlamaExtractAdapter.resolveApiKey(env);
        String base = env.get("LLAMA_EXTRACT_BASE_URL");
        this.baseUrl = (base == null || base.isBlank())
                ? LlamaExtractAdapter.DEFAULT_BASE_URL
                : trimSlash(base);
        String t = env.get("LLAMA_PARSE_TIER");
        this.tier = (t == null || t.isBlank()) ? DEFAULT_TIER : t.trim();
        String v = env.get("LLAMA_PARSE_VERSION");
        this.version = (v == null || v.isBlank()) ? DEFAULT_VERSION : v.trim();
        this.timeoutMs = parseLong(
                env.get(LlamaExtractAdapter.KEY_TIMEOUT_MS),
                LlamaExtractAdapter.DEFAULT_TIMEOUT_MS);
        this.residency = env.get(DataResidency.ENV);
    }

    /** True when a LlamaParse/LlamaCloud API key is configured. */
    public static boolean hasApiKey() {
        return LlamaExtractAdapter.hasApiKey();
    }

    /**
     * Uploads and parses a document, polling until a terminal status.
     * Tier is {@link #DEFAULT_TIER} unless overridden.
     *
     * @param file original PDF/image (required)
     * @return parse job with id and page text/markdown
     * @throws ExtractException on missing key, HTTP failure, timeout, or failed job
     */
    public ParseJob parse(Path file) throws ExtractException {
        return parse(file, null);
    }

    /**
     * Uploads and parses with an explicit tier (or {@code null} for the default / env tier).
     *
     * @param file original PDF/image (required)
     * @param tier override tier; null uses configured default
     * @return parse job with id and page text/markdown
     * @throws ExtractException on missing key, HTTP failure, timeout, or failed job
     */
    public ParseJob parse(Path file, ParseTier tier) throws ExtractException {
        return parse(file, tier, null);
    }

    /**
     * Uploads and parses, optionally restricting the job to {@code targetPages}
     * (1-based). Null or empty sends the whole file, which is the historical call.
     */
    @Override
    public ParseJob parse(Path file, ParseTier tier, java.util.List<Integer> targetPages)
            throws ExtractException {
        if (DataResidency.locked(residency)) {
            throw new ExtractException(
                    "LlamaParseClient blocked: data residency is local_only");
        }
        Objects.requireNonNull(file, "file");
        if (apiKey == null || apiKey.isBlank()) {
            throw new ExtractException(
                    "LlamaParse API key is not set (LLAMA_CLOUD_API_KEY or llamaparse_api_key)");
        }
        if (!Files.isRegularFile(file)) {
            throw new ExtractException("parse file not found: " + file);
        }
        String tierWire = tier != null ? tier.wireName() : this.tier;
        String pagesWire = PageSelection.wire(targetPages);
        long start = System.nanoTime();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 30_000L)))
                .build();
        try {
            String jobId = uploadAndCreate(client, file, tierWire, pagesWire);
            String pagesHint = pagesWire == null || pagesWire.isBlank() ? "all pages"
                    : "pages " + pagesWire;
            CloudJobProgress.note("LlamaParse upload ok; job " + jobId + " (" + tierWire + ", "
                    + pagesHint + "); polling until complete…");
            JsonNode job = poll(client, jobId, start);
            String status = statusOf(job);
            if (!"COMPLETED".equals(status)) {
                String message = job.path("job").path("error_message").asText(null);
                if (message == null || message.isBlank()) {
                    message = job.path("error_message").asText(null);
                }
                throw new ExtractException("LlamaParse job " + jobId + " ended " + status
                        + (message == null || message.isBlank() ? "" : ": " + message));
            }
            return toJob(jobId, job);
        } catch (HttpTimeoutException e) {
            throw new ExtractException("LlamaParse timed out after " + timeoutMs + " ms", e);
        } catch (ExtractException e) {
            throw e;
        } catch (IOException e) {
            throw new ExtractException("LlamaParse I/O failure: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractException("LlamaParse interrupted", e);
        }
    }

    /**
     * Polls an existing parse job id (for example one created earlier).
     *
     * @param jobId LlamaParse job id ({@code pjb-…})
     * @return parse job snapshot
     * @throws ExtractException on HTTP failure or incomplete job
     */
    public ParseJob get(String jobId) throws ExtractException {
        if (DataResidency.locked(residency)) {
            throw new ExtractException(
                    "LlamaParseClient blocked: data residency is local_only");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ExtractException("LlamaParse API key is not set");
        }
        HttpClient client = HttpClient.newHttpClient();
        try {
            JsonNode job = send(client, getRequest(jobId), "parse get");
            return toJob(jobId, job);
        } catch (IOException e) {
            throw new ExtractException("LlamaParse get failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractException("LlamaParse interrupted", e);
        }
    }

    /**
     * Parse job snapshot.
     *
     * @param jobId parse job id
     * @param status job status
     * @param pageMarkdown markdown pages in order (may be empty)
     * @param pageText plain text pages in order (may be empty)
     * @param raw full job JSON for audit
     * @param credits credits billed for this job ({@code job.usage.credits}); {@code null}
     *     when billing has not recorded it yet
     */
    public record ParseJob(
            String jobId,
            String status,
            java.util.List<String> pageMarkdown,
            java.util.List<String> pageText,
            JsonNode raw,
            Double credits) {

        /** Concatenated markdown (all pages). */
        public String markdown() {
            return String.join("\n\n", pageMarkdown);
        }

        /** Concatenated plain text (all pages). */
        public String text() {
            return String.join("\n", pageText);
        }

        /** Snapshot with credits unknown (used when the usage expand is not requested). */
        public ParseJob(String jobId, String status, java.util.List<String> pageMarkdown,
                java.util.List<String> pageText, JsonNode raw) {
            this(jobId, status, pageMarkdown, pageText, raw, null);
        }
    }

    private String uploadAndCreate(HttpClient client, Path file, String tierWire, String pagesWire)
            throws IOException, InterruptedException, ExtractException {
        String boundary = "----tevparse" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, file, tierWire, pagesWire);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + PARSE_PATH + "/upload"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        JsonNode json = send(client, request, "parse upload");
        String id = json.path("id").asText(null);
        if (id == null || id.isBlank()) {
            id = json.path("job").path("id").asText(null);
        }
        if (id == null || id.isBlank()) {
            throw new ExtractException("LlamaParse upload returned no job id");
        }
        return id;
    }

    private JsonNode poll(HttpClient client, String jobId, long startNanos)
            throws IOException, InterruptedException, ExtractException {
        long deadline = startNanos + timeoutMs * 1_000_000L;
        while (true) {
            JsonNode job = send(client, getRequest(jobId), "parse get");
            String status = statusOf(job);
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                return job;
            }
            CloudJobProgress.pollStatus("LlamaParse", jobId, status,
                    (System.nanoTime() - startNanos) / 1_000_000L);
            if (System.nanoTime() >= deadline) {
                throw new ExtractException("LlamaParse job " + jobId
                        + " still " + status + " after " + timeoutMs + " ms");
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
    }

    private HttpRequest getRequest(String jobId) {
        URI uri = URI.create(baseUrl + PARSE_PATH + "/" + jobId
                + "?expand=markdown,text,usage");
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(5_000L, timeoutMs)))
                .header("Authorization", "Bearer " + apiKey)
                .header("accept", "application/json")
                .GET()
                .build();
    }

    private static String statusOf(JsonNode job) {
        String nested = job.path("job").path("status").asText("");
        return nested.isBlank() ? job.path("status").asText("") : nested;
    }

    private static ParseJob toJob(String jobId, JsonNode job) {
        JsonNode root = job.has("job") ? job.path("job") : job;
        JsonNode markdownNode = job.has("markdown") ? job.path("markdown") : root.path("markdown");
        JsonNode textNode = job.has("text") ? job.path("text") : root.path("text");
        java.util.List<String> markdown = pagesOf(markdownNode, "markdown");
        java.util.List<String> text = pagesOf(textNode, "text");
        int pages = Math.max(markdown.size(), text.size());
        java.util.List<String> cleaned = new java.util.ArrayList<>(pages);
        for (int i = 0; i < pages; i++) {
            String page = i < markdown.size() ? markdown.get(i) : "";
            if (page == null || page.isBlank()) {
                page = i < text.size() ? text.get(i) : "";
            }
            cleaned.add(ParseMarkdown.clean(page));
        }
        java.util.List<String> alignedText = new java.util.ArrayList<>(pages);
        for (int i = 0; i < pages; i++) {
            String page = i < text.size() ? text.get(i) : "";
            if (page == null || page.isBlank()) {
                page = i < markdown.size() ? markdown.get(i) : "";
            }
            alignedText.add(page);
        }
        return new ParseJob(jobId, statusOf(job), cleaned, alignedText, job, creditsOf(job));
    }

    /** Credits billed for the job, nested under {@code job.usage.credits}; null when absent. */
    private static Double creditsOf(JsonNode job) {
        JsonNode usage = job.path("job").path("usage");
        if (!usage.isObject()) {
            usage = job.path("usage");
        }
        if (usage.hasNonNull("credits")) {
            return usage.get("credits").asDouble();
        }
        return null;
    }

    private static java.util.List<String> pagesOf(JsonNode node, String valueField) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (node.isMissingNode() || node.isNull()) {
            return out;
        }
        if (node.isTextual()) {
            out.add(node.asText());
            return out;
        }
        JsonNode pages = node.path("pages");
        if (pages.isArray()) {
            for (JsonNode page : pages) {
                int pageNumber = page.path("page_number").asInt(out.size() + 1);
                while (out.size() < pageNumber) {
                    out.add("");
                }
                String value = page.path(valueField).asText("");
                out.set(pageNumber - 1, value);
            }
        }
        return out;
    }

    private byte[] multipart(String boundary, Path file, String tierWire, String pagesWire)
            throws IOException {
        String name = file.getFileName() == null ? "document" : file.getFileName().toString();
        byte[] bytes = Files.readAllBytes(file);
        // v2 nests page selection under page_ranges (top-level target_pages → HTTP 400).
        String configuration = "{\"tier\":\"" + tierWire + "\",\"version\":\"" + version + "\"";
        if (pagesWire != null && !pagesWire.isBlank()) {
            configuration += ",\"page_ranges\":{\"target_pages\":\"" + pagesWire + "\"}";
        }
        configuration += "}";
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
        out.write(("Content-Disposition: form-data; name=\"configuration\"" + crlf + crlf)
                .getBytes(StandardCharsets.UTF_8));
        out.write(configuration.getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private JsonNode send(HttpClient client, HttpRequest request, String what)
            throws IOException, InterruptedException, ExtractException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (code < 200 || code >= 300) {
            throw new ExtractException("LlamaParse " + what + " HTTP " + code
                    + (body.isBlank() ? "" : ": " + body.substring(0, Math.min(200, body.length()))));
        }
        try {
            return MAPPER.readTree(body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            throw new ExtractException("LlamaParse " + what + " returned malformed JSON", e);
        }
    }

    private static String trimSlash(String base) {
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
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
}
