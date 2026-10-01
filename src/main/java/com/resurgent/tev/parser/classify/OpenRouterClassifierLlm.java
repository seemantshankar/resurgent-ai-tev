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
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * OpenRouter adapter for region layout + Layer A. Reasoning effort is low.
 */
public final class OpenRouterClassifierLlm implements ClassifierLlm {

    static final String DEFAULT_URL = "https://openrouter.ai/api/v1/chat/completions";
    public static final int LAYER_A_MAX_COMPLETION_TOKENS = 4_096;
    public static final int REGION_LAYOUT_MAX_COMPLETION_TOKENS = 32_768;
    public static final int LAYER_A_LABEL_MAX_ITEMS = 32;

    private final CompletionsClient client;

    public OpenRouterClassifierLlm(String apiKey, String model) {
        this(new HttpCompletionsClient(apiKey, model, DEFAULT_URL));
    }

    /** Models in the order to try them; a failed request moves to the next model. */
    public OpenRouterClassifierLlm(String apiKey, List<String> models) {
        this(chainOf(apiKey, models));
    }

    private static CompletionsClient chainOf(String apiKey, List<String> models) {
        if (models.size() == 1) {
            return new HttpCompletionsClient(apiKey, models.get(0), DEFAULT_URL, deadlinesFor(0, 1));
        }
        List<FallbackCompletionsClient.Link> links = new java.util.ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            links.add(new FallbackCompletionsClient.Link(models.get(i),
                    new HttpCompletionsClient(apiKey, models.get(i), DEFAULT_URL, deadlinesFor(i, models.size()))));
        }
        return new FallbackCompletionsClient(links);
    }

    /**
     * How long one request may take before the model is given up on. A model with another
     * behind it fails fast, because a healthy small call answers in a few seconds and waiting
     * longer only delays the fallback. The last model has nothing behind it, so it is patient.
     */
    static Deadlines deadlinesFor(int index, int chainLength) {
        return index == chainLength - 1 ? Deadlines.LAST_RESORT : Deadlines.FAIL_FAST;
    }

    /** Whole-exchange limits: {@code small} for ordinary calls, {@code large} for big batches. */
    record Deadlines(Duration small, Duration large) {
        static final Deadlines FAIL_FAST = new Deadlines(Duration.ofSeconds(45), Duration.ofSeconds(180));
        static final Deadlines LAST_RESORT = new Deadlines(Duration.ofSeconds(120), Duration.ofSeconds(240));

        /** Large when the model may write a long answer or the prompt itself is big. */
        Duration forRequest(int promptChars, int maxCompletionTokens) {
            boolean large = maxCompletionTokens >= 16_384 || promptChars >= 40_000;
            return large ? this.large : this.small;
        }
    }

    OpenRouterClassifierLlm(CompletionsClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public UsageTotals usageTotals() {
        return client.usageTotals();
    }

    /** Sum of OpenRouter {@code usage} across calls made by this client. */
    public record UsageTotals(
            long calls, long promptTokens, long completionTokens, double costUsd, long costMissing) {
        public static UsageTotals empty() {
            return new UsageTotals(0, 0, 0, 0, 0);
        }

        public boolean costKnown() {
            return costMissing == 0;
        }
    }

    @Override
    public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
        return client.perModel(c -> {
            String system = RegionLayoutPromptAssembler.SYSTEM;
            String user = RegionLayoutPromptAssembler.userMessage(prompt);
            CompletionResult result = c.completeJson(system, user, REGION_LAYOUT_MAX_COMPLETION_TOKENS);
            if (result.truncated()) {
                // Deliberation ate the budget; retry with a lead-with-JSON instruction.
                result = c.completeJson(
                        system + "\nPrior response was TRUNCATED. Emit the JSON object immediately,"
                                + " shortest form, no deliberation.",
                        user + "\n\nRetry after truncation: JSON object only.",
                        REGION_LAYOUT_MAX_COMPLETION_TOKENS);
                if (result.truncated()) {
                    throw new IllegalStateException(
                            "OpenRouter region layout truncated after retry: finish="
                                    + result.finishReason());
                }
            }
            return RegionLayoutResponseParser.parse(result.content());
        });
    }

    @Override
    public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
        return client.perModel(c -> {
            String system = LayerAPromptAssembler.SYSTEM;
            String user = LayerAPromptAssembler.userMessage(prompt);
            List<String> families = prompt.scheduleFamilies();
            CompletionResult result = c.completeLayerA(system, user, families);
            if (result.truncated()) {
                throw new IllegalStateException(
                        "OpenRouter Layer A truncated: finish=" + result.finishReason());
            }
            return LayerAResponseParser.parse(result.content());
        });
    }

    @Override
    public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
        return client.perModel(c -> {
            String system = LayerBPromptAssembler.SYSTEM;
            String user = LayerBPromptAssembler.userMessage(prompt);
            CompletionResult result = c.completeJson(system, user, REGION_LAYOUT_MAX_COMPLETION_TOKENS);
            if (result.truncated()) {
                result = c.completeJson(
                        system + "\nPrior response was TRUNCATED. Emit the JSON object immediately.",
                        user + "\n\nRetry after truncation: JSON object only.",
                        REGION_LAYOUT_MAX_COMPLETION_TOKENS);
                if (result.truncated()) {
                    throw new IllegalStateException(
                            "OpenRouter Layer B truncated after retry: finish=" + result.finishReason());
                }
            }
            return LayerBResponseParser.parse(result.content());
        });
    }

    @Override
    public String classifyCellJson(String systemPrompt, String userPrompt, int maxTokens) {
        return client.perModel(c -> validJson(c.completeJson(systemPrompt, userPrompt, maxTokens).content()));
    }

    /** The content when it parses as JSON; otherwise the model failed and the next one is tried. */
    private static String validJson(String content) {
        try {
            new ObjectMapper().readTree(content);
            return content;
        } catch (Exception e) {
            throw new IllegalStateException("model returned invalid JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public String classifyLayerAJson(String userPrompt, int maxTokens) {
        String systemPrompt = """
                You are a financial document region classifier. Classify each candidate region/table.
                For each region, determine: scheduleFamily, triage (MAIN/HELPER), relevance (PRIMARY/SECONDARY/TERTIARY),
                row labels, column headers, packet default head, and a brief description.
                Return a JSON array with one object per candidate.""";
        return client.perModel(c -> validJson(c.completeJson(systemPrompt, userPrompt, maxTokens).content()));
    }

    @Override
    public java.util.Map<String, List<RegionProposal>> proposeRegionsBatch(
            java.util.List<RegionLayoutPrompt> prompts) {
        return client.perModel(c -> {
            String system = """
                    You classify regions on multiple Excel worksheets for a TEV clean financial-model extract.
                    For each worksheet, return ONLY JSON (no markdown):
                    {
                      "main": [{"bbox":"A1:F10","label":"...","why":"..."}],
                      "helper": [{"bbox":"A1:F10","label":"...","why":"..."}],
                      "scratch": [{"bbox":"A1:F10","label":"...","why":"..."}]
                    }

                    DEFINITIONS
                    - main = RETAIN for the model
                    - helper = EXCLUDE from core model (audit / breakout / variance) — do not double-count
                    - scratch = OMIT (floating orphans only)

                    CRITICAL — DO NOT OVER-SPLIT MAINS
                    - Prefer a SMALL number of large mains (ideally ~4 section mains + optional sheet title band).
                    - A section MAIN must be ONE bbox that includes, together:
                      section header + item/detail rows + official section total / Lacs summary figures
                      for that section.
                    - NEVER emit a main that is only a header row.
                    - NEVER emit a main that is only a total row detached from its section.
                    - Document title / units may be one small main OR absorbed into the first section main
                      — but do NOT put floating scratch digits into main.

                    HELPER (keep separate from mains)
                    - Inline BoQ / vendor quote / green-style breakout blocks.
                    - Side variance/scenario pads: alternate + difference columns. Prefer one helper bbox
                      (or few) for that pad band, not dozens of singletons.
                    - Official cost lines on the primary estimate column belong on the SECTION MAIN;
                      only the breakout math under them is helper.
                    - NEVER widen a section MAIN into side pad columns. If a column sits to the right of
                      the primary amount column and looks like another amount band, treat it as helper
                      unless you are sure it is part of the official section schedule.

                    WHEN UNSURE — READ THE FORMULAS
                    - The dump shows formulas (not only cached values). Use them before deciding.
                    - Difference / variance / scenario formulas (e.g. =I9-J9, =J-K, compare-to-quote)
                      → that column (or pad) is HELPER, not main.
                    - Formulas that only restate a primary amount in Lacs / another unit, or pull the
                      same line for a check → HELPER tear-out, not an extension of the main bbox.
                    - Primary section totals that SUM the official estimate column stay on MAIN;
                      do not fold neighboring variance columns into that main just because they
                      share the same rows.
                    - If still ambiguous after reading formulas, prefer a separate helper bbox over
                      merging the side pad into main.

                    SCRATCH
                    - Only unanchored floats / far-right checksums with no section label.
                    - Do NOT mark intermediate cells inside a helper breakout as separate scratch.

                    bboxes must use addresses present in the dump. No invented cells.
                    """;
            String user = formatBatchRegionLayoutPrompt(prompts);
            CompletionResult result = c.completeJson(system, user, REGION_LAYOUT_MAX_COMPLETION_TOKENS);
            if (result.truncated()) {
                result = c.completeJson(
                        system + "\nPrior response was TRUNCATED. Emit the JSON objects immediately, shortest form.",
                        user + "\n\nRetry after truncation: JSON objects only.",
                        REGION_LAYOUT_MAX_COMPLETION_TOKENS);
                if (result.truncated()) {
                    throw new IllegalStateException(
                            "OpenRouter region layout batch truncated after retry: finish="
                                    + result.finishReason());
                }
            }
            return RegionLayoutBatchResponseParser.parse(result.content());
        });
    }

    private static String formatBatchRegionLayoutPrompt(java.util.List<RegionLayoutPrompt> prompts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Classify regions on ").append(prompts.size()).append(" worksheets:\n\n");
        for (int i = 0; i < prompts.size(); i++) {
            RegionLayoutPrompt p = prompts.get(i);
            sb.append("=== WORKSHEET ").append(i + 1).append(": ").append(p.sheetName()).append(" ===\n");
            sb.append("Cell dump (address \\t value-or-formula; formulas are authoritative — use them when a side column might be variance/helper):\n");
            sb.append(p.cellDump()).append("\n\n");
        }
        sb.append("Return a JSON object with sheet names as keys, each containing main/helper/scratch arrays:\n");
        sb.append("{\"SheetName1\": {\"main\": [...], \"helper\": [...], \"scratch\": [...]}, \"SheetName2\": {...}, ...}\n");
        return sb.toString();
    }

    record CompletionResult(
            String content,
            Integer promptTokens,
            Integer completionTokens,
            Integer reasoningTokens,
            String finishReason,
            boolean truncated) {
        CompletionResult {
            Objects.requireNonNull(content, "content");
        }

        static CompletionResult of(String content) {
            return new CompletionResult(content, null, null, null, null, false);
        }
    }

    interface CompletionsClient {
        CompletionResult completeJson(String system, String user, int maxCompletionTokens);

        CompletionResult completeLayerA(String system, String user, List<String> scheduleFamilies);

        default UsageTotals usageTotals() {
            return UsageTotals.empty();
        }

        /**
         * Run a whole operation (call + parse) against this client. A model chain overrides
         * this to try each model in turn when the operation throws.
         */
        default <T> T perModel(java.util.function.Function<CompletionsClient, T> operation) {
            return operation.apply(this);
        }
    }

    @FunctionalInterface
    interface HttpExchange {
        ExchangeResponse send(String jsonBody, Duration deadline) throws Exception;
    }

    record ExchangeResponse(int statusCode, String body) {}

    static final class HttpCompletionsClient implements CompletionsClient {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final String model;
        private final HttpExchange exchange;
        private final LongAdder calls = new LongAdder();
        private final LongAdder promptTokens = new LongAdder();
        private final LongAdder completionTokens = new LongAdder();
        private final DoubleAdder costUsd = new DoubleAdder();
        private final LongAdder costMissing = new LongAdder();

        private final Deadlines deadlines;

        HttpCompletionsClient(String apiKey, String model, String url) {
            this(apiKey, model, url, Deadlines.LAST_RESORT);
        }

        /**
         * The deadline bounds the whole exchange, response body included. The request
         * timeout alone only covers waiting for the response to begin: a provider that sends
         * headers and then goes quiet would otherwise block the run indefinitely.
         */
        HttpCompletionsClient(String apiKey, String model, String url, Deadlines deadlines) {
            Objects.requireNonNull(apiKey, "apiKey");
            this.model = Objects.requireNonNull(model, "model");
            this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
            URI uri = URI.create(url);
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
            this.exchange = (body, deadline) -> {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(deadline)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .header("HTTP-Referer",
                                "https://github.com/seemantshankar/resurgent-ai-tev")
                        .header("X-OpenRouter-Title", "TEV Parser")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                java.util.concurrent.CompletableFuture<HttpResponse<String>> pending =
                        http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                try {
                    HttpResponse<String> response =
                            pending.get(deadline.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    return new ExchangeResponse(response.statusCode(), response.body());
                } catch (java.util.concurrent.TimeoutException e) {
                    pending.cancel(true);
                    throw new HttpTimeoutException(
                            "no complete response within " + deadline.toSeconds() + "s");
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw e;
                } catch (InterruptedException e) {
                    pending.cancel(true);
                    Thread.currentThread().interrupt();
                    throw e;
                }
            };
        }

        @Override
        public CompletionResult completeJson(String system, String user, int maxCompletionTokens) {
            try {
                System.err.println("[http-client] completeJson: model=" + model + ", maxTokens=" + maxCompletionTokens + ", userLen=" + user.length());
                System.err.flush();
                return post(requestBody(model, system, user, null, maxCompletionTokens),
                        deadlines.forRequest(user.length(), maxCompletionTokens));
            } catch (Exception e) {
                throw new IllegalStateException("OpenRouter request build failed: " + e.getMessage(), e);
            }
        }

        @Override
        public CompletionResult completeLayerA(
                String system, String user, List<String> scheduleFamilies) {
            try {
                return post(requestBody(
                        model,
                        system,
                        user,
                        layerAResponseFormat(scheduleFamilies),
                        LAYER_A_MAX_COMPLETION_TOKENS),
                        deadlines.forRequest(user.length(), LAYER_A_MAX_COMPLETION_TOKENS));
            } catch (Exception e) {
                throw new IllegalStateException("OpenRouter request build failed: " + e.getMessage(), e);
            }
        }

        @Override
        public UsageTotals usageTotals() {
            return new UsageTotals(
                    calls.sum(),
                    promptTokens.sum(),
                    completionTokens.sum(),
                    costUsd.sum(),
                    costMissing.sum());
        }

        private void recordUsage(String body, CompletionResult result) {
            calls.increment();
            if (result.promptTokens() != null) {
                promptTokens.add(result.promptTokens());
            }
            if (result.completionTokens() != null) {
                completionTokens.add(result.completionTokens());
            }
            Double cost = costUsd(body);
            if (cost == null) {
                costMissing.increment();
            } else {
                costUsd.add(cost);
            }
        }

        private static Double costUsd(String body) {
            try {
                JsonNode usage = MAPPER.readTree(body).path("usage");
                if (usage.path("cost").isNumber()) {
                    return usage.path("cost").asDouble();
                }
                if (usage.path("total_cost").isNumber()) {
                    return usage.path("total_cost").asDouble();
                }
            } catch (Exception ignored) {
                return null;
            }
            return null;
        }

        private CompletionResult post(String body, Duration deadline) {
            long startNanos = System.nanoTime();
            try {
                System.err.println("[http-client] Sending request to OpenRouter...");
                System.err.flush();
                ExchangeResponse response = exchange.send(body, deadline);
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                System.err.println("[http-client] Got response HTTP " + response.statusCode() + " after " + elapsedMs + "ms");
                System.err.flush();
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException(
                            "OpenRouter HTTP " + response.statusCode()
                                    + " " + snippet(response.body()));
                }
                CompletionResult result = contentWithUsage(response.body());
                recordUsage(response.body(), result);
                return result;
            } catch (IllegalStateException e) {
                throw e;
            } catch (HttpTimeoutException e) {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                System.err.println("[http-client] HTTP TIMEOUT after " + elapsedMs + "ms");
                System.err.flush();
                throw new IllegalStateException("OpenRouter call timed out after " + elapsedMs + "ms", e);
            } catch (Exception e) {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                System.err.println("[http-client] HTTP ERROR after " + elapsedMs + "ms: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                System.err.flush();
                throw new IllegalStateException("OpenRouter call failed: " + e.getMessage(), e);
            }
        }

        static String requestBody(
                String model,
                String system,
                String user,
                ObjectNode responseFormat,
                int maxCompletionTokens)
                throws Exception {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", model);
            root.put("temperature", 0.05);
            root.put("max_completion_tokens", maxCompletionTokens);
            ObjectNode reasoning = root.putObject("reasoning");
            reasoning.put("effort", "low");
            ObjectNode provider = root.putObject("provider");
            provider.put("data_collection", "deny");
            if (responseFormat != null) {
                root.set("response_format", responseFormat);
            } else {
                ObjectNode format = root.putObject("response_format");
                format.put("type", "json_object");
            }
            ArrayNode messages = root.putArray("messages");
            ObjectNode systemNode = messages.addObject();
            systemNode.put("role", "system");
            systemNode.put("content", system);
            ObjectNode userNode = messages.addObject();
            userNode.put("role", "user");
            userNode.put("content", user);
            return MAPPER.writeValueAsString(root);
        }

        private static ObjectNode layerAResponseFormat(List<String> families) {
            ObjectNode format = MAPPER.createObjectNode();
            format.put("type", "json_schema");
            ObjectNode jsonSchema = format.putObject("json_schema");
            jsonSchema.put("name", "layer_a_judgment");
            jsonSchema.put("strict", true);
            ObjectNode schema = jsonSchema.putObject("schema");
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            ObjectNode properties = schema.putObject("properties");
            enumProperty(properties, "scheduleFamily",
                    "one of the offered schedule families, or none",
                    familyEnum(families));
            enumProperty(properties, "triage", "main|scratch|orphan",
                    Triage.MAIN, Triage.SCRATCH, Triage.ORPHAN);
            enumProperty(properties, "relevance", "primary|supporting|noise",
                    Relevance.PRIMARY, Relevance.SUPPORTING, Relevance.NOISE);
            stringArrayProperty(properties, "rowLabels", "CORE row labels", LAYER_A_LABEL_MAX_ITEMS);
            stringArrayProperty(
                    properties, "columnHeaders", "CORE column headers", LAYER_A_LABEL_MAX_ITEMS);
            ObjectNode head = properties.putObject("packetDefaultHead");
            ArrayNode headType = head.putArray("type");
            headType.add("string");
            headType.add("null");
            ObjectNode suggested = properties.putObject("suggestedFamily");
            ArrayNode suggestedType = suggested.putArray("type");
            suggestedType.add("string");
            suggestedType.add("null");
            objectProperty(properties, "about",
                    "Short paragraph (~4-8 sentences) grounded in CORE: identity, "
                            + "model function, row/column contents, and how amounts "
                            + "should be used (primary vs helper tear-out)");
            ArrayNode required = schema.putArray("required");
            required.add("scheduleFamily");
            required.add("suggestedFamily");
            required.add("triage");
            required.add("relevance");
            required.add("rowLabels");
            required.add("columnHeaders");
            required.add("packetDefaultHead");
            required.add("about");
            return format;
        }

        private static String[] familyEnum(List<String> families) {
            List<String> offered = families == null || families.isEmpty()
                    ? ScheduleFamily.seeds()
                    : families;
            String[] values = new String[offered.size() + 1];
            for (int i = 0; i < offered.size(); i++) {
                values[i] = offered.get(i);
            }
            values[values.length - 1] = ScheduleFamily.NONE;
            return values;
        }

        private static void objectProperty(ObjectNode properties, String name, String description) {
            ObjectNode node = properties.putObject(name);
            node.put("type", "string");
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
                ObjectNode properties, String name, String description, int maxItems) {
            ObjectNode node = properties.putObject(name);
            node.put("type", "array");
            node.put("description", description);
            node.put("maxItems", maxItems);
            node.putObject("items").put("type", "string");
        }

        private static CompletionResult contentWithUsage(String body) throws Exception {
            JsonNode root = MAPPER.readTree(body);
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("OpenRouter returned empty message content");
            }
            String finish = root.path("choices").path(0).path("finish_reason").asText(null);
            boolean truncated = finish != null && finish.toLowerCase(Locale.ROOT).contains("length");
            JsonNode usage = root.path("usage");
            Integer promptTokens = integral(usage, "prompt_tokens");
            Integer completionTokens = integral(usage, "completion_tokens");
            Integer reasoningTokens = null;
            JsonNode details = usage.path("completion_tokens_details");
            if (details.path("reasoning_tokens").isIntegralNumber()) {
                reasoningTokens = details.path("reasoning_tokens").intValue();
            }
            return new CompletionResult(
                    content, promptTokens, completionTokens, reasoningTokens, finish, truncated);
        }

        private static Integer integral(JsonNode node, String field) {
            JsonNode value = node.path(field);
            return value.isIntegralNumber() ? value.intValue() : null;
        }

        private static String snippet(String body) {
            if (body == null) {
                return "";
            }
            String trimmed = body.trim().replace('\n', ' ');
            return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
        }
    }
}
