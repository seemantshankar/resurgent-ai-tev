package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * OpenRouter chat-completions adapter for Layer A. Config-gated; tests inject
 * a {@link CompletionsClient} so the suite stays fake/offline.
 */
public final class OpenRouterClassifierLlm implements ClassifierLlm {

    static final String DEFAULT_URL = "https://openrouter.ai/api/v1/chat/completions";

    private final CompletionsClient client;

    public OpenRouterClassifierLlm(String apiKey, String model) {
        this(new HttpCompletionsClient(apiKey, model, DEFAULT_URL));
    }

    OpenRouterClassifierLlm(CompletionsClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
        String system = LayerAPromptAssembler.SYSTEM;
        String user = LayerAPromptAssembler.userMessage(prompt);
        String completion = client.complete(system, user);
        try {
            return LayerAResponseParser.parse(completion);
        } catch (RuntimeException first) {
            String retry = client.complete(
                    system + "\nReturn only a valid JSON object. triage must be main|scratch|orphan;"
                            + " relevance must be primary|supporting|noise. No markdown.",
                    user + "\n\nYour previous JSON was rejected: " + first.getMessage());
            try {
                return LayerAResponseParser.parse(retry);
            } catch (RuntimeException second) {
                throw new IllegalStateException(
                        "OpenRouter Layer A invalid after retry: " + second.getMessage(), second);
            }
        }
    }

    @Override
    public java.util.List<LayerBLineJudgment> classifyLayerB(LayerBPrompt prompt) {
        String system = LayerBPromptAssembler.SYSTEM;
        String user = LayerBPromptAssembler.userMessage(prompt);
        String completion = client.completeLayerB(system, user);
        try {
            return LayerBResponseParser.parse(completion);
        } catch (RuntimeException first) {
            String retry = client.completeLayerB(
                    system + "\nReturn only {\"lines\":[...]} with amountRole add|deduct|total|helper."
                            + " No markdown.",
                    user + "\n\nYour previous JSON was rejected: " + first.getMessage());
            try {
                return LayerBResponseParser.parse(retry);
            } catch (RuntimeException second) {
                throw new IllegalStateException(
                        "OpenRouter Layer B invalid after retry: " + second.getMessage(), second);
            }
        }
    }

    interface CompletionsClient {
        String complete(String system, String user);

        default String completeLayerB(String system, String user) {
            return complete(system, user);
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
                            .timeout(Duration.ofMinutes(2))
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
            return completeWithFormat(system, user, layerAResponseFormat());
        }

        @Override
        public String completeLayerB(String system, String user) {
            return completeWithFormat(system, user, layerBResponseFormat());
        }

        private String completeWithFormat(String system, String user, ObjectNode responseFormat) {
            try {
                String body = requestBody(model, system, user, responseFormat);
                IllegalStateException last = null;
                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    ExchangeResponse response = exchange.send(body);
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return content(response.body());
                    }
                    if (response.statusCode() != 429 || attempt == MAX_ATTEMPTS) {
                        throw new IllegalStateException(
                                "OpenRouter HTTP " + response.statusCode()
                                        + " " + snippet(response.body()));
                    }
                    last = new IllegalStateException(
                            "OpenRouter HTTP 429 " + snippet(response.body()));
                    sleeper.sleep(retryDelay(response.retryAfter(), attempt));
                }
                throw last != null ? last : new IllegalStateException("OpenRouter HTTP 429");
            } catch (IllegalStateException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("OpenRouter call interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("OpenRouter call failed: " + e.getMessage(), e);
            }
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
         * Official OpenRouter chat-completions body for GLM Flash Latest:
         * mandatory {@code reasoning.effort} of {@code low|high|max}, and
         * {@code response_format} {@code json_schema} (not {@code json_object}).
         */
        static String requestBody(String model, String system, String user) throws Exception {
            return requestBody(model, system, user, layerAResponseFormat());
        }

        static String requestBody(
                String model, String system, String user, ObjectNode responseFormat)
                throws Exception {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", model);
            root.put("temperature", 0);
            ObjectNode reasoning = root.putObject("reasoning");
            reasoning.put("effort", "low");
            reasoning.put("exclude", true);
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
            stringArrayProperty(properties, "rowLabels", "distinct row-axis labels; empty if none");
            stringArrayProperty(properties, "columnHeaders",
                    "distinct column-axis headers; empty if none");
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
            lines.put("description", "money-line bindings; empty if none");
            ObjectNode items = lines.putObject("items");
            items.put("type", "object");
            items.put("additionalProperties", false);
            ObjectNode itemProps = items.putObject("properties");
            objectProperty(itemProps, "coord", "amount cell coord in the Packet");
            objectProperty(itemProps, "verbatim", "client label evidence");
            objectProperty(itemProps, "path", "nomenclature leaf path");
            enumProperty(itemProps, "amountRole",
                    "add, deduct, total, or helper",
                    AmountRole.ADD, AmountRole.DEDUCT, AmountRole.TOTAL, AmountRole.HELPER);
            stringArrayProperty(itemProps, "aliases", "optional soft-leaf synonyms");
            ObjectNode confidence = itemProps.putObject("confidence");
            ArrayNode confidenceType = confidence.putArray("type");
            confidenceType.add("number");
            confidenceType.add("null");
            confidence.put("description", "0..1 or null");
            ArrayNode itemRequired = items.putArray("required");
            itemRequired.add("coord");
            itemRequired.add("verbatim");
            itemRequired.add("path");
            itemRequired.add("amountRole");
            itemRequired.add("aliases");
            itemRequired.add("confidence");
            ArrayNode required = schema.putArray("required");
            required.add("lines");
            return format;
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
                ObjectNode properties, String name, String description) {
            ObjectNode node = properties.putObject(name);
            node.put("type", "array");
            node.put("description", description);
            node.putObject("items").put("type", "string");
        }

        static String content(String responseJson) throws Exception {
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
            JsonNode content = choice.path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new IllegalStateException(
                        "OpenRouter response had no message content "
                                + snippet(responseJson));
            }
            if (content.isObject() || content.isArray()) {
                return MAPPER.writeValueAsString(content);
            }
            String text = content.asText();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException(
                        "OpenRouter response had empty message content "
                                + snippet(responseJson));
            }
            return text;
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
