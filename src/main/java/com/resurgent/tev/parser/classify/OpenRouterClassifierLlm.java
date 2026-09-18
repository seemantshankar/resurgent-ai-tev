package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * OpenRouter chat-completions adapter for Layer A/B. Config-gated; tests inject
 * a {@link CompletionsClient} so the suite stays fake/offline.
 */
public final class OpenRouterClassifierLlm implements ClassifierLlm, FormulaGlossLlm {

    static final String DEFAULT_URL = "https://openrouter.ai/api/v1/chat/completions";

    private final CompletionsClient client;
    private final List<LlmCallMetric> metrics = new CopyOnWriteArrayList<>();

    public OpenRouterClassifierLlm(String apiKey, String model) {
        this(new HttpCompletionsClient(apiKey, model, DEFAULT_URL));
    }

    OpenRouterClassifierLlm(CompletionsClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public List<LlmCallMetric> metrics() {
        return List.copyOf(metrics);
    }

    @Override
    public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
        String system = LayerAPromptAssembler.SYSTEM;
        String user = LayerAPromptAssembler.userMessage(prompt);
        long started = System.nanoTime();
        CompletionResult first = client.completeDetailed(system, user);
        try {
            rejectIfTruncated("A", first);
            LayerAJudgment judgment = LayerAResponseParser.parse(first.content());
            recordMetric("A", user, first, started, 1);
            return judgment;
        } catch (TruncatedCompletionException e) {
            // Deliberation ate the whole budget and left no JSON. Retry with room
            // for the answer and an instruction to lead with it.
            CompletionResult retry = client.completeDetailed(
                    system + "\nPrior response was TRUNCATED with no usable JSON. Emit the"
                            + " JSON object immediately, shortest form, no deliberation."
                            + " Empty rowLabels/columnHeaders. No markdown.",
                    user + "\n\nRetry after truncation: answer with the JSON object only.",
                    LAYER_A_RETRY_MAX_COMPLETION_TOKENS);
            try {
                rejectIfTruncated("A", retry);
                LayerAJudgment judgment = LayerAResponseParser.parse(retry.content());
                recordMetric("A", user, retry, started, 2);
                return judgment;
            } catch (TruncatedCompletionException second) {
                recordMetric("A", user, retry, started, 2);
                throw new TruncatedCompletionException(
                        "OpenRouter Layer A truncated/invalid after retry: " + second.getMessage(),
                        second);
            } catch (RuntimeException second) {
                recordMetric("A", user, retry, started, 2);
                throw new IllegalStateException(
                        "OpenRouter Layer A invalid after retry: " + second.getMessage(), second);
            }
        } catch (RuntimeException firstError) {
            CompletionResult retry = client.completeDetailed(
                    system + "\nReturn only a compact valid JSON object. triage must be"
                            + " main|scratch|orphan; relevance must be primary|supporting|noise."
                            + " Empty rowLabels/columnHeaders unless essential. No markdown.",
                    user + "\n\nYour previous JSON was rejected: " + firstError.getMessage(),
                    LAYER_A_RETRY_MAX_COMPLETION_TOKENS);
            try {
                rejectIfTruncated("A", retry);
                LayerAJudgment judgment = LayerAResponseParser.parse(retry.content());
                recordMetric("A", user, retry, started, 2);
                return judgment;
            } catch (RuntimeException second) {
                recordMetric("A", user, retry, started, 2);
                throw new IllegalStateException(
                        "OpenRouter Layer A invalid after retry: " + second.getMessage(), second);
            }
        }
    }

    @Override
    public List<LayerBLineJudgment> classifyLayerB(LayerBPrompt prompt) {
        String system = LayerBPromptAssembler.SYSTEM;
        LayerBPromptAssembler.Assembled assembled = LayerBPromptAssembler.assemble(prompt);
        String user = assembled.userMessage();
        int candidateLines = assembled.index().amounts().size();
        long started = System.nanoTime();
        CompletionResult first = client.completeLayerBDetailed(system, user, candidateLines);
        try {
            rejectIfTruncated("B", first);
            List<LayerBLineJudgment> lines =
                    acceptLayerB(first.content(), assembled.index());
            recordMetric("B", user, first, started, 1);
            return lines;
        } catch (TruncatedCompletionException e) {
            // Soft safeguard: one truncation retry asking for lines-only (no soft spam).
            CompletionResult retry = client.completeLayerBDetailed(
                    system + "\nPrior response was TRUNCATED. Return only {\"lines\":[...],\"soft\":[]}"
                            + " with soft empty. Prefer existing paths. No markdown.",
                    user + "\n\nRetry after truncation: omit soft leaves; bind existing paths"
                            + " only. Prior response was rejected: " + e.getMessage(),
                    candidateLines);
            try {
                rejectIfTruncated("B", retry);
                List<LayerBLineJudgment> lines =
                        acceptLayerB(retry.content(), assembled.index());
                recordMetric("B", user, retry, started, 2);
                return lines;
            } catch (RuntimeException second) {
                recordMetric("B", user, retry, started, 2);
                throw new IllegalStateException(
                        "OpenRouter Layer B truncated/invalid after retry: " + second.getMessage(),
                        second);
            }
        } catch (RuntimeException firstError) {
            CompletionResult retry = client.completeLayerBDetailed(
                    system + "\nReturn only {\"lines\":[[cellIndex,pathIndex,roleCode],...],"
                            + "\"soft\":[]} with roleCode 0=add 1=deduct 2=total 3=helper."
                            + " No markdown.",
                    user + "\n\nYour previous JSON was rejected: " + firstError.getMessage(),
                    candidateLines);
            try {
                rejectIfTruncated("B", retry);
                List<LayerBLineJudgment> lines =
                        acceptLayerB(retry.content(), assembled.index());
                recordMetric("B", user, retry, started, 2);
                return lines;
            } catch (RuntimeException second) {
                recordMetric("B", user, retry, started, 2);
                throw new IllegalStateException(
                        "OpenRouter Layer B invalid after retry: " + second.getMessage(), second);
            }
        }
    }

    @Override
    public String gloss(FormulaGlossPrompt prompt) {
        String system = FormulaGlossPromptAssembler.SYSTEM
                + "\nReturn a JSON object {\"gloss\":\"...\"} only.";
        String user = FormulaGlossPromptAssembler.userMessage(prompt);
        long started = System.nanoTime();
        CompletionResult result = client.completePlainTextDetailed(system, user, 400);
        recordMetric("G", user, result, started, 1);
        return extractGloss(result.content());
    }

    static String extractGloss(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        try {
            JsonNode root = new ObjectMapper().readTree(trimmed);
            if (root != null && root.hasNonNull("gloss")) {
                String gloss = root.get("gloss").asText();
                return gloss == null || gloss.isBlank() ? null : gloss.trim();
            }
        } catch (Exception ignored) {
            // Fall through to plain prose.
        }
        if (trimmed.startsWith("{")) {
            return null;
        }
        return trimmed;
    }

    private static List<LayerBLineJudgment> acceptLayerB(
            String content, LayerBPromptIndex index) {
        LayerBResponseParser.Parsed parsed = LayerBResponseParser.parseDetailed(content, index);
        for (String dropped : parsed.dropped()) {
            System.err.println("OpenRouter B dropped item: " + dropped);
        }
        return parsed.lines();
    }

    private static void rejectIfTruncated(String layer, CompletionResult result) {
        if (result != null && result.truncated()) {
            throw new TruncatedCompletionException(
                    "OpenRouter Layer " + layer + " truncated (finish_reason="
                            + result.finishReason() + ")");
        }
    }

    private void recordMetric(
            String layer, String user, CompletionResult result, long startedNanos, int parseAttempts) {
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        int visible = result.content() == null ? 0 : result.content().length();
        metrics.add(new LlmCallMetric(
                layer,
                user == null ? 0 : user.length(),
                result.promptTokens(),
                result.completionTokens(),
                result.reasoningTokens(),
                visible,
                result.finishReason(),
                result.truncated(),
                durationMs,
                parseAttempts,
                result.httpAttempts()));
        System.err.printf(
                "OpenRouter %s promptBytes=%d promptTok=%s completionTok=%s reasoningTok=%s"
                        + " visibleChars=%d finish=%s truncated=%s durationMs=%d"
                        + " parseAttempts=%d httpAttempts=%d%n",
                layer,
                user == null ? 0 : user.length(),
                result.promptTokens() == null ? "?" : result.promptTokens(),
                result.completionTokens() == null ? "?" : result.completionTokens(),
                result.reasoningTokens() == null ? "?" : result.reasoningTokens(),
                visible,
                result.finishReason() == null ? "?" : result.finishReason(),
                result.truncated(),
                durationMs,
                parseAttempts,
                result.httpAttempts());
    }

    /** One timed OpenRouter call for live-IT reporting (#122). */
    public record LlmCallMetric(
            String layer,
            int promptBytes,
            Integer promptTokens,
            Integer completionTokens,
            Integer reasoningTokens,
            int visibleContentChars,
            String finishReason,
            boolean truncated,
            long durationMs,
            int parseAttempts,
            int httpAttempts) {

        /** Backward-compatible view used by older report printers. */
        public int attempts() {
            return parseAttempts;
        }
    }

    record CompletionResult(
            String content,
            Integer promptTokens,
            Integer completionTokens,
            Integer reasoningTokens,
            String finishReason,
            boolean truncated,
            int httpAttempts) {
        CompletionResult {
            Objects.requireNonNull(content, "content");
        }

        static CompletionResult of(String content) {
            return new CompletionResult(content, null, null, null, null, false, 1);
        }
    }

    /** Thrown when the provider stopped because the completion token budget was hit. */
    public static final class TruncatedCompletionException extends IllegalStateException {
        TruncatedCompletionException(String message) {
            super(message);
        }

        TruncatedCompletionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Firm Layer A completion budget — the disposition JSON itself is small, so
     * this stays a mitigation for model verbosity (hidden deliberation burning
     * the budget before any JSON), not a ceiling driven by JSON size.
     */
    public static final int LAYER_A_MAX_COMPLETION_TOKENS = 4_096;
    /**
     * Layer A retry budget after truncation. The firm budget is ample for the
     * JSON, but a model that deliberates past it emits no visible content at
     * all; the retry buys room for the answer rather than re-losing the call.
     * Like the first-call budget, this is a verbosity mitigation, not a
     * JSON-size ceiling.
     */
    public static final int LAYER_A_RETRY_MAX_COMPLETION_TOKENS = 12_288;
    /**
     * OpenRouter rejects {@code reasoning.effort} and {@code reasoning.max_tokens}
     * on the same request. Layer A uses {@code effort=low} plus
     * {@link #LAYER_A_MAX_COMPLETION_TOKENS}; this constant is the intended
     * reasoning budget if a provider later allows both.
     */
    public static final int LAYER_A_REASONING_MAX_TOKENS = 128;
    public static final int LAYER_B_TOKENS_PER_CANDIDATE_LINE = 24;
    public static final int LAYER_B_SOFT_BUDGET_TOKENS = 400;
    public static final int LAYER_B_MIN_COMPLETION_TOKENS = 800;
    public static final int LAYER_B_MAX_COMPLETION_TOKENS = 8_000;
    public static final int LAYER_A_LABEL_MAX_ITEMS = 32;

    /** Generous Layer B completion allowance from the number of bindable amount lines. */
    public static int layerBMaxCompletionTokens(int candidateLines) {
        int lines = Math.max(0, candidateLines);
        int estimated = lines * LAYER_B_TOKENS_PER_CANDIDATE_LINE + LAYER_B_SOFT_BUDGET_TOKENS;
        return Math.min(
                LAYER_B_MAX_COMPLETION_TOKENS, Math.max(LAYER_B_MIN_COMPLETION_TOKENS, estimated));
    }

    interface CompletionsClient {
        String complete(String system, String user);

        default CompletionResult completeDetailed(String system, String user) {
            return CompletionResult.of(complete(system, user));
        }

        default CompletionResult completeDetailed(
                String system, String user, int maxCompletionTokens) {
            return completeDetailed(system, user);
        }

        default String completeLayerB(String system, String user) {
            return complete(system, user);
        }

        default CompletionResult completeLayerBDetailed(String system, String user) {
            return completeLayerBDetailed(system, user, 0);
        }

        default CompletionResult completeLayerBDetailed(
                String system, String user, int candidateLines) {
            return CompletionResult.of(completeLayerB(system, user));
        }

        default String completePlainText(String system, String user, int maxCompletionTokens) {
            return completePlainTextDetailed(system, user, maxCompletionTokens).content();
        }

        default CompletionResult completePlainTextDetailed(
                String system, String user, int maxCompletionTokens) {
            return CompletionResult.of(complete(system, user));
        }
    }

    @FunctionalInterface
    interface HttpExchange {
        ExchangeResponse send(String jsonBody) throws Exception;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    record ExchangeResponse(int statusCode, String body, Optional<String> retryAfter) {
        ExchangeResponse {
            Objects.requireNonNull(retryAfter, "retryAfter");
        }
    }

    static final class HttpCompletionsClient implements CompletionsClient {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        static final int MAX_ATTEMPTS = 4;
        static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

        /** Provider returned a 2xx body whose message content is empty or null. */
        static final class NoMessageContentException extends RuntimeException {
            NoMessageContentException(String message) {
                super(message);
            }
        }
        /**
         * Per-send HTTP budget. Must stay shorter than
         * {@link ClassifyLimits#DEFAULT_ATTEMPT_DEADLINE} so a stalled TCP read
         * can time out and retry before the classify attempt watchdog fires.
         */
        static final Duration HTTP_TIMEOUT = Duration.ofSeconds(75);

        private final String model;
        private final HttpExchange exchange;
        private final Sleeper sleeper;

        HttpCompletionsClient(String apiKey, String model, String url) {
            this(apiKey, model, url, null, delay -> Thread.sleep(delay.toMillis()));
        }

        HttpCompletionsClient(
                String apiKey,
                String model,
                String url,
                HttpExchange exchange,
                Sleeper sleeper) {
            Objects.requireNonNull(apiKey, "apiKey");
            this.model = Objects.requireNonNull(model, "model");
            URI uri = URI.create(url);
            this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
            if (exchange != null) {
                this.exchange = exchange;
            } else {
                HttpClient http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .build();
                this.exchange = body -> {
                    HttpRequest request = HttpRequest.newBuilder(uri)
                            .timeout(HTTP_TIMEOUT)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .header("HTTP-Referer",
                                    "https://github.com/seemantshankar/resurgent-ai-tev")
                            .header("X-OpenRouter-Title", "TEV Parser")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> response = http.send(
                            request, HttpResponse.BodyHandlers.ofString());
                    return new ExchangeResponse(
                            response.statusCode(),
                            response.body(),
                            response.headers().firstValue("Retry-After"));
                };
            }
        }

        @Override
        public String complete(String system, String user) {
            return completeDetailed(system, user).content();
        }

        @Override
        public CompletionResult completeDetailed(String system, String user) {
            return completeDetailed(system, user, LAYER_A_MAX_COMPLETION_TOKENS);
        }

        @Override
        public CompletionResult completeDetailed(
                String system, String user, int maxCompletionTokens) {
            return completeWithFormat(
                    system,
                    user,
                    layerAResponseFormat(),
                    maxCompletionTokens,
                    0);
        }

        @Override
        public String completeLayerB(String system, String user) {
            return completeLayerBDetailed(system, user, 0).content();
        }

        @Override
        public CompletionResult completeLayerBDetailed(String system, String user) {
            return completeLayerBDetailed(system, user, 0);
        }

        @Override
        public CompletionResult completeLayerBDetailed(
                String system, String user, int candidateLines) {
            return completeWithFormat(
                    system,
                    user,
                    layerBResponseFormat(),
                    layerBMaxCompletionTokens(candidateLines),
                    0);
        }

        @Override
        public CompletionResult completePlainTextDetailed(
                String system, String user, int maxCompletionTokens) {
            return completeWithFormat(
                    system,
                    user,
                    formulaGlossResponseFormat(),
                    Math.max(64, maxCompletionTokens),
                    0);
        }

        private static ObjectNode formulaGlossResponseFormat() {
            ObjectNode format = MAPPER.createObjectNode();
            format.put("type", "json_schema");
            ObjectNode jsonSchema = format.putObject("json_schema");
            jsonSchema.put("name", "formula_gloss");
            jsonSchema.put("strict", true);
            ObjectNode schema = jsonSchema.putObject("schema");
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            ObjectNode properties = schema.putObject("properties");
            objectProperty(properties, "gloss",
                    "1-3 short sentences explaining the formula; no amounts invented");
            ArrayNode required = schema.putArray("required");
            required.add("gloss");
            return format;
        }

        private CompletionResult completeWithFormat(
                String system,
                String user,
                ObjectNode responseFormat,
                int maxCompletionTokens,
                int reasoningMaxTokens) {
            try {
                String body = requestBody(
                        model, system, user, responseFormat, maxCompletionTokens, reasoningMaxTokens);
                Exception last = null;
                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    try {
                        ExchangeResponse response = exchange.send(body);
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            return contentWithUsage(response.body(), attempt);
                        }
                        if (response.statusCode() != 429 || attempt == MAX_ATTEMPTS) {
                            throw new IllegalStateException(
                                    "OpenRouter HTTP " + response.statusCode()
                                            + " " + snippet(response.body()));
                        }
                        last = new IllegalStateException(
                                "OpenRouter HTTP 429 " + snippet(response.body()));
                        sleeper.sleep(retryDelay(response.retryAfter(), attempt));
                    } catch (IllegalStateException e) {
                        throw e;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("OpenRouter call interrupted", e);
                    } catch (Exception e) {
                        if (!retryableTransport(e) || attempt == MAX_ATTEMPTS) {
                            throw new IllegalStateException(
                                    "OpenRouter call failed: " + e.getMessage(), e);
                        }
                        last = e;
                        sleeper.sleep(retryDelay(Optional.empty(), attempt));
                    }
                }
                if (last instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(
                        "OpenRouter call failed: "
                                + (last != null ? last.getMessage() : "exhausted retries"),
                        last);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("OpenRouter call failed: " + e.getMessage(), e);
            }
        }

        static boolean retryableTransport(Throwable error) {
            if (error instanceof InterruptedException) {
                return false;
            }
            for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
                if (cursor instanceof InterruptedException) {
                    return false;
                }
                if (cursor instanceof NoMessageContentException) {
                    return true;
                }
                if (cursor instanceof HttpTimeoutException
                        || cursor instanceof java.net.SocketTimeoutException) {
                    return true;
                }
                if (cursor instanceof java.io.IOException) {
                    String message = cursor.getMessage();
                    if (message != null) {
                        String lower = message.toLowerCase(Locale.ROOT);
                        if (lower.contains("timed out")
                                || lower.contains("timeout")
                                || lower.contains("reset")
                                || lower.contains("broken pipe")
                                || lower.contains("eof")
                                || lower.contains("refused")) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        static Duration retryDelay(Optional<String> retryAfter, int attempt) {
            if (retryAfter != null && retryAfter.isPresent()) {
                String raw = retryAfter.get().trim();
                try {
                    long seconds = Long.parseLong(raw);
                    if (seconds >= 0) {
                        Duration parsed = Duration.ofSeconds(seconds);
                        return parsed.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // Fall through to exponential backoff.
                }
            }
            long millis = Math.min(MAX_BACKOFF.toMillis(), 500L << Math.max(0, attempt - 1));
            return Duration.ofMillis(millis);
        }

        /**
         * OpenRouter chat-completions body: {@code response_format} {@code json_schema},
         * and reasoning as <em>either</em> {@code max_tokens} (Layer A budget) or
         * {@code effort} (Layer B). The provider rejects both on one request.
         */
        static String requestBody(String model, String system, String user) throws Exception {
            return requestBody(
                    model,
                    system,
                    user,
                    layerAResponseFormat(),
                    LAYER_A_MAX_COMPLETION_TOKENS,
                    0);
        }

        static String requestBody(
                String model, String system, String user, ObjectNode responseFormat)
                throws Exception {
            String schemaName = responseFormat.path("json_schema").path("name").asText("");
            boolean layerA = "layer_a_judgment".equals(schemaName);
            int maxTokens = layerA ? LAYER_A_MAX_COMPLETION_TOKENS : LAYER_B_MIN_COMPLETION_TOKENS;
            int reasoning = 0;
            return requestBody(model, system, user, responseFormat, maxTokens, reasoning);
        }

        static String requestBody(
                String model,
                String system,
                String user,
                ObjectNode responseFormat,
                int maxCompletionTokens,
                int reasoningMaxTokens)
                throws Exception {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", model);
            root.put("temperature", 0);
            root.put("max_tokens", maxCompletionTokens);
            ObjectNode reasoning = root.putObject("reasoning");
            reasoning.put("exclude", true);
            if (reasoningMaxTokens > 0) {
                reasoning.put("max_tokens", reasoningMaxTokens);
            } else {
                reasoning.put("effort", "low");
            }
            ObjectNode provider = root.putObject("provider");
            provider.put("require_parameters", true);
            provider.put("data_collection", "deny");
            ArrayNode plugins = root.putArray("plugins");
            plugins.addObject().put("id", "response-healing");
            root.set("response_format", responseFormat);
            ArrayNode messages = root.putArray("messages");
            ObjectNode systemNode = messages.addObject();
            systemNode.put("role", "system");
            systemNode.put("content", system);
            ObjectNode userNode = messages.addObject();
            userNode.put("role", "user");
            userNode.put("content", user);
            return MAPPER.writeValueAsString(root);
        }

        private static ObjectNode layerAResponseFormat() {
            ObjectNode format = MAPPER.createObjectNode();
            format.put("type", "json_schema");
            ObjectNode jsonSchema = format.putObject("json_schema");
            jsonSchema.put("name", "layer_a_judgment");
            jsonSchema.put("strict", true);
            ObjectNode schema = jsonSchema.putObject("schema");
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            ObjectNode properties = schema.putObject("properties");
            objectProperty(properties, "scheduleFamily",
                    "snake_case family (capex_detail, means_of_finance, profit_and_loss, "
                            + "balance_sheet, cash_flow, assumptions, project_summary, "
                            + "or another short snake_case name)");
            enumProperty(properties, "triage",
                    "main keeps the Packet on the main schedule; scratch is a working paper; "
                            + "orphan is unattached",
                    Triage.MAIN, Triage.SCRATCH, Triage.ORPHAN);
            enumProperty(properties, "relevance",
                    "primary, supporting, or noise; must be noise when triage is scratch or orphan",
                    Relevance.PRIMARY, Relevance.SUPPORTING, Relevance.NOISE);
            stringArrayProperty(properties, "rowLabels",
                    "distinct row-axis labels; empty if none; at most "
                            + LAYER_A_LABEL_MAX_ITEMS + " items",
                    LAYER_A_LABEL_MAX_ITEMS);
            stringArrayProperty(properties, "columnHeaders",
                    "distinct column-axis headers; empty if none; at most "
                            + LAYER_A_LABEL_MAX_ITEMS + " items",
                    LAYER_A_LABEL_MAX_ITEMS);
            ObjectNode head = properties.putObject("packetDefaultHead");
            ArrayNode headType = head.putArray("type");
            headType.add("string");
            headType.add("null");
            head.put("description", "nomenclature path from the ontology slice, or null");
            ArrayNode required = schema.putArray("required");
            required.add("scheduleFamily");
            required.add("triage");
            required.add("relevance");
            required.add("rowLabels");
            required.add("columnHeaders");
            required.add("packetDefaultHead");
            return format;
        }

        static ObjectNode layerBResponseFormat() {
            ObjectNode format = MAPPER.createObjectNode();
            format.put("type", "json_schema");
            ObjectNode jsonSchema = format.putObject("json_schema");
            jsonSchema.put("name", "layer_b_bindings");
            jsonSchema.put("strict", true);
            ObjectNode schema = jsonSchema.putObject("schema");
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            ObjectNode properties = schema.putObject("properties");

            ObjectNode lines = properties.putObject("lines");
            lines.put("type", "array");
            lines.put("description",
                    "bindings as [cellIndex, pathIndex, roleCode]; roleCode 0=add 1=deduct 2=total 3=helper");
            ObjectNode lineItems = lines.putObject("items");
            lineItems.put("type", "array");
            lineItems.put("minItems", 3);
            lineItems.put("maxItems", 3);
            lineItems.putObject("items").put("type", "integer");

            ObjectNode soft = properties.putObject("soft");
            soft.put("type", "array");
            soft.put("description", "new soft leaves only; empty when binding existing leaves");
            ObjectNode softItems = soft.putObject("items");
            softItems.put("type", "object");
            softItems.put("additionalProperties", false);
            ObjectNode softProps = softItems.putObject("properties");
            integerProperty(softProps, "c", "cellIndex into amounts[]");
            integerProperty(softProps, "pp", "parentPathIndex into paths[] (mid-level)");
            objectProperty(softProps, "n", "new soft leaf name under parent");
            stringArrayProperty(softProps, "a", "optional aliases for the soft leaf");
            integerProperty(softProps, "r", "roleCode 0=add 1=deduct 2=total 3=helper");
            ArrayNode softRequired = softItems.putArray("required");
            softRequired.add("c");
            softRequired.add("pp");
            softRequired.add("n");
            softRequired.add("a");
            softRequired.add("r");

            ArrayNode required = schema.putArray("required");
            required.add("lines");
            required.add("soft");
            return format;
        }

        private static void objectProperty(ObjectNode properties, String name, String description) {
            ObjectNode node = properties.putObject(name);
            node.put("type", "string");
            node.put("description", description);
        }

        private static void integerProperty(ObjectNode properties, String name, String description) {
            ObjectNode node = properties.putObject(name);
            node.put("type", "integer");
            node.put("description", description);
        }

        private static void enumProperty(
                ObjectNode properties, String name, String description, String... values) {
            ObjectNode node = properties.putObject(name);
            node.put("type", "string");
            node.put("description", description);
            ArrayNode enums = node.putArray("enum");
            for (String value : values) {
                enums.add(value);
            }
        }

        private static void stringArrayProperty(
                ObjectNode properties, String name, String description) {
            stringArrayProperty(properties, name, description, -1);
        }

        private static void stringArrayProperty(
                ObjectNode properties, String name, String description, int maxItems) {
            ObjectNode node = properties.putObject(name);
            node.put("type", "array");
            node.put("description", description);
            node.putObject("items").put("type", "string");
            if (maxItems > 0) {
                node.put("maxItems", maxItems);
            }
        }

        static String content(String responseJson) throws Exception {
            return contentWithUsage(responseJson, 1).content();
        }

        static CompletionResult contentWithUsage(String responseJson) throws Exception {
            return contentWithUsage(responseJson, 1);
        }

        static CompletionResult contentWithUsage(String responseJson, int httpAttempts)
                throws Exception {
            JsonNode root = MAPPER.readTree(responseJson);
            JsonNode error = root.path("error");
            if (error.isObject()) {
                throw new IllegalStateException(
                        "OpenRouter error: " + snippet(error.toString()));
            }
            JsonNode choice = root.path("choices").path(0);
            JsonNode choiceError = choice.path("error");
            if (choiceError.isObject()) {
                throw new IllegalStateException(
                        "OpenRouter choice error: " + snippet(choiceError.toString()));
            }
            String finishReason = choice.path("finish_reason").isMissingNode()
                            || choice.path("finish_reason").isNull()
                    ? null
                    : choice.path("finish_reason").asText();
            boolean truncated = "length".equalsIgnoreCase(finishReason);
            JsonNode content = choice.path("message").path("content");
            String text = "";
            if (!content.isMissingNode() && !content.isNull()) {
                if (content.isObject() || content.isArray()) {
                    text = MAPPER.writeValueAsString(content);
                } else {
                    text = content.asText();
                }
            }
            if (text == null || text.isBlank()) {
                if (truncated) {
                    text = "{}";
                } else {
                    throw new NoMessageContentException(
                            "OpenRouter response had no message content "
                                    + snippet(responseJson));
                }
            }
            JsonNode usage = root.path("usage");
            Integer promptTokens = usage.path("prompt_tokens").isIntegralNumber()
                    ? usage.path("prompt_tokens").intValue()
                    : null;
            Integer completionTokens = usage.path("completion_tokens").isIntegralNumber()
                    ? usage.path("completion_tokens").intValue()
                    : null;
            Integer reasoningTokens = null;
            JsonNode details = usage.path("completion_tokens_details");
            if (details.path("reasoning_tokens").isIntegralNumber()) {
                reasoningTokens = details.path("reasoning_tokens").intValue();
            } else if (usage.path("reasoning_tokens").isIntegralNumber()) {
                reasoningTokens = usage.path("reasoning_tokens").intValue();
            }
            return new CompletionResult(
                    text,
                    promptTokens,
                    completionTokens,
                    reasoningTokens,
                    finishReason,
                    truncated,
                    Math.max(1, httpAttempts));
        }

        private static String snippet(String value) {
            if (value == null) {
                return "";
            }
            String trimmed = value.replaceAll("\\s+", " ").trim();
            return trimmed.length() <= 400 ? trimmed : trimmed.substring(0, 400);
        }
    }
}
