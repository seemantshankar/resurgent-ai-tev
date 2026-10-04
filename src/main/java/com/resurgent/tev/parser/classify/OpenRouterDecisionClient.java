package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/** OpenRouter Decisions API ({@code /api/alpha/decisions}) adapter for cell kind + scale. */
final class OpenRouterDecisionClient implements CellDecisionClient {

    static final String URL = "https://openrouter.ai/api/alpha/decisions";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    private static final Map<String, String> KINDS = new java.util.LinkedHashMap<>();
    private static final Map<String, String> SCALES = new java.util.LinkedHashMap<>();

    static {
        KINDS.put("money", "A currency amount: a cost, price, revenue, expense, budget or other rupee/dollar value");
        KINDS.put("quantity", "A physical amount measured in a unit such as sqm, kg, kW, metres or hours");
        KINDS.put("rate", "A price or amount per unit, such as rupees per sqm or a monthly rental rate");
        KINDS.put("percent", "A percentage or proportion of a whole, such as a tax rate, margin or share");
        KINDS.put("count", "A number of discrete items, people or occurrences, such as units or months");
        KINDS.put("ratio", "A dimensionless ratio or multiple, such as 1.5x or a coverage ratio");
        SCALES.put("unit", "Stated in plain units; no scale word applies");
        SCALES.put("thousand", "Stated in thousands (000s)");
        SCALES.put("lakh", "Stated in lakhs / lacs (100,000)");
        SCALES.put("million", "Stated in millions");
        SCALES.put("crore", "Stated in crores (10,000,000)");
        SCALES.put("billion", "Stated in billions");
    }

    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String apiKey;
    private final LongAdder calls = new LongAdder();
    private final LongAdder failures = new LongAdder();

    OpenRouterDecisionClient(String apiKey, String model) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = Objects.requireNonNull(model, "model");
    }

    String model() {
        return model;
    }

    String stats() {
        return calls.sum() + " calls, " + failures.sum() + " failed";
    }

    @Override
    public Decision decide(String state) throws Exception {
        return decide(state, true);
    }

    @Override
    public Decision decide(String state, boolean askScale) throws Exception {
        calls.increment();
        long start = System.nanoTime();
        long activityId = LlmActivity.GLOBAL.begin(model); // so the heartbeat sees decision calls in flight
        boolean succeeded = false;
        try {
            String body = post(requestBody(model, state, askScale));
            Decision decision = parse(body, askScale);
            JsonNode usage = MAPPER.readTree(body).path("usage");
            LlmStats.GLOBAL.recordCall(model, usage.path("input_tokens").asLong(0),
                    usage.path("output_tokens").asLong(0),
                    usage.path("cost").isNumber() ? usage.path("cost").asDouble() : null,
                    (System.nanoTime() - start) / 1_000_000);
            succeeded = true;
            return decision;
        } catch (Exception e) {
            failures.increment();
            LlmStats.GLOBAL.recordFailedCall(model, (System.nanoTime() - start) / 1_000_000);
            throw e;
        } finally {
            LlmActivity.GLOBAL.end(activityId, succeeded);
        }
    }

    static String requestBody(String model, String state) throws Exception {
        return requestBody(model, state, true);
    }

    static String requestBody(String model, String state, boolean askScale) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.set("state", stateNode(state));
        ObjectNode questions = root.putObject("questions");
        question(questions, "kind", "What kind of quantity is the number in the cell? Judge from region.about, row_label, column_label, part_of and row_note, not from the size of the number.", KINDS);
        if (askScale) {
            question(questions, "scale", "In what scale is the number stated? A region.about, row_label or column_label may say Lacs, Crores, thousands or millions; if none does, the scale is unit.", SCALES);
        }
        return MAPPER.writeValueAsString(root);
    }

    /** A JSON object state is sent as an object, as the API prefers for multi-part context; text stays text. */
    private static JsonNode stateNode(String state) {
        if (state.stripLeading().startsWith("{")) {
            try {
                JsonNode parsed = MAPPER.readTree(state);
                if (parsed.isObject()) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // not JSON after all; send it as text
            }
        }
        return MAPPER.getNodeFactory().textNode(state);
    }

    private static void question(ObjectNode questions, String name, String instructions, Map<String, String> options) {
        ObjectNode q = questions.putObject(name);
        q.put("type", "choice");
        q.put("instructions", instructions);
        ObjectNode criteria = q.putObject("criteria");
        options.forEach(criteria::put);
    }

    private String post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                .timeout(DEADLINE)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://github.com/seemantshankar/resurgent-ai-tev")
                .header("X-OpenRouter-Title", "TEV Parser")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("decisions HTTP " + response.statusCode() + ": "
                    + response.body().substring(0, Math.min(200, response.body().length())));
        }
        return response.body();
    }

    static Decision parse(String body) throws Exception {
        return parse(body, true);
    }

    static Decision parse(String body, boolean scaleAsked) throws Exception {
        JsonNode answers = MAPPER.readTree(body).path("answers");
        JsonNode kind = answers.path("kind");
        JsonNode scale = answers.path("scale");
        if (!kind.hasNonNull("choice") || (scaleAsked && !scale.hasNonNull("choice"))) {
            throw new IllegalStateException("decisions response lacks kind/scale choice");
        }
        if (!scaleAsked) {
            return new Decision(
                    kind.get("choice").asText().trim().toLowerCase(java.util.Locale.ROOT),
                    kind.path("confidence").asDouble(0.0), null, 0.0);
        }
        return new Decision(
                kind.get("choice").asText().trim().toLowerCase(java.util.Locale.ROOT),
                kind.path("confidence").asDouble(0.0),
                scale.get("choice").asText().trim().toLowerCase(java.util.Locale.ROOT),
                scale.path("confidence").asDouble(0.0));
    }
}
