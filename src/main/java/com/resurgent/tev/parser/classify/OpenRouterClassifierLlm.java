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

    interface CompletionsClient {
        String complete(String system, String user);
    }

    static final class HttpCompletionsClient implements CompletionsClient {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final String apiKey;
        private final String model;
        private final URI uri;
        private final HttpClient http;

        HttpCompletionsClient(String apiKey, String model, String url) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
            this.model = Objects.requireNonNull(model, "model");
            this.uri = URI.create(url);
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
        }

        @Override
        public String complete(String system, String user) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMinutes(2))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .header("HTTP-Referer", "https://github.com/seemantshankar/resurgent-ai-tev")
                        .header("X-OpenRouter-Title", "TEV Parser")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody(model, system, user)))
                        .build();
                HttpResponse<String> response = http.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException(
                            "OpenRouter HTTP " + response.statusCode()
                                    + " " + snippet(response.body()));
                }
                return content(response.body());
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("OpenRouter call failed: " + e.getMessage(), e);
            }
        }

        /**
         * Official OpenRouter chat-completions body for GLM Flash Latest:
         * mandatory {@code reasoning.effort} of {@code low|high|max}, and
         * {@code response_format} {@code json_schema} (not {@code json_object}).
         */
        static String requestBody(String model, String system, String user) throws Exception {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", model);
            root.put("temperature", 0);
            ObjectNode reasoning = root.putObject("reasoning");
            reasoning.put("effort", "low");
            reasoning.put("exclude", true);
            ObjectNode provider = root.putObject("provider");
            provider.put("require_parameters", true);
            ArrayNode plugins = root.putArray("plugins");
            plugins.addObject().put("id", "response-healing");
            root.set("response_format", layerAResponseFormat());
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
                    "primary, supporting, or noise",
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
