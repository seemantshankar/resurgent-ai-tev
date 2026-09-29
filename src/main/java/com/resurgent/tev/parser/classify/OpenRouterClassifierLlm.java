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
        String system = RegionLayoutPromptAssembler.SYSTEM;
        String user = RegionLayoutPromptAssembler.userMessage(prompt);
        CompletionResult result = client.completeJson(system, user, REGION_LAYOUT_MAX_COMPLETION_TOKENS);
        if (result.truncated()) {
            // Deliberation ate the budget; retry with a lead-with-JSON instruction.
            result = client.completeJson(
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
    }

    @Override
    public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
        String system = LayerAPromptAssembler.SYSTEM;
        String user = LayerAPromptAssembler.userMessage(prompt);
        List<String> families = prompt.scheduleFamilies();
        CompletionResult result = client.completeLayerA(system, user, families);
        if (result.truncated()) {
            throw new IllegalStateException(
                    "OpenRouter Layer A truncated: finish=" + result.finishReason());
        }
        return LayerAResponseParser.parse(result.content());
    }

    @Override
    public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
        String system = LayerBPromptAssembler.SYSTEM;
        String user = LayerBPromptAssembler.userMessage(prompt);
        CompletionResult result = client.completeJson(system, user, REGION_LAYOUT_MAX_COMPLETION_TOKENS);
        if (result.truncated()) {
            result = client.completeJson(
                    system + "\nPrior response was TRUNCATED. Emit the JSON object immediately.",
                    user + "\n\nRetry after truncation: JSON object only.",
                    REGION_LAYOUT_MAX_COMPLETION_TOKENS);
            if (result.truncated()) {
                throw new IllegalStateException(
                        "OpenRouter Layer B truncated after retry: finish=" + result.finishReason());
            }
        }
        return LayerBResponseParser.parse(result.content());
    }

    @Override
    public String classifyCellJson(String systemPrompt, String userPrompt, int maxTokens) {
        CompletionResult result = client.completeJson(systemPrompt, userPrompt, maxTokens);
        return result.content();
    }

    @Override
    public String classifyLayerAJson(String userPrompt, int maxTokens) {
        String systemPrompt = """
                You are a financial document region classifier. Classify each candidate region/table.
                For each region, determine: scheduleFamily, triage (MAIN/HELPER), relevance (PRIMARY/SECONDARY/TERTIARY),
                row labels, column headers, packet default head, and a brief description.
                Return a JSON array with one object per candidate.""";
        CompletionResult result = client.completeJson(systemPrompt, userPrompt, maxTokens);
        return result.content();
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
    }

    @FunctionalInterface
    interface HttpExchange {
        ExchangeResponse send(String jsonBody) throws Exception;
    }

    record ExchangeResponse(int statusCode, String body) {}

    static final class HttpCompletionsClient implements CompletionsClient {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        static final Duration HTTP_TIMEOUT = Duration.ofSeconds(180);

        private final String model;
        private final HttpExchange exchange;
        private final LongAdder calls = new LongAdder();
        private final LongAdder promptTokens = new LongAdder();
        private final LongAdder completionTokens = new LongAdder();
        private final DoubleAdder costUsd = new DoubleAdder();
        private final LongAdder costMissing = new LongAdder();

        HttpCompletionsClient(String apiKey, String model, String url) {
            Objects.requireNonNull(apiKey, "apiKey");
            this.model = Objects.requireNonNull(model, "model");
            URI uri = URI.create(url);
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
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                return new ExchangeResponse(response.statusCode(), response.body());
            };
        }

        @Override
        public CompletionResult completeJson(String system, String user, int maxCompletionTokens) {
            try {
                System.err.println("[http-client] completeJson: model=" + model + ", maxTokens=" + maxCompletionTokens + ", userLen=" + user.length());
                System.err.flush();
                return post(requestBody(model, system, user, null, maxCompletionTokens));
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
                        LAYER_A_MAX_COMPLETION_TOKENS));
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

        private CompletionResult post(String body) {
            long startNanos = System.nanoTime();
            try {
                System.err.println("[http-client] Sending request to OpenRouter...");
                System.err.flush();
                ExchangeResponse response = exchange.send(body);
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
