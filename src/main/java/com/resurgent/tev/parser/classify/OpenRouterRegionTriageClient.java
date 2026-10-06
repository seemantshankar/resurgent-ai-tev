package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** The region triage question put to a decision model on OpenRouter's decisions endpoint. */
final class OpenRouterRegionTriageClient implements RegionTriageClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    private static final Map<String, String> OPTIONS = new LinkedHashMap<>();

    static {
        OPTIONS.put("main", "Content of the model that an analyst would read: a labelled schedule, table, statement, "
                + "assumptions block, summary or restatement, a title or header band, or a fragment or continuation "
                + "of such a table. Totals and outputs that nothing else reads are still main.");
        OPTIONS.put("scratch", "The author's own working or check area, not model content: reconciliation, Check or "
                + "Diff rows that compare two totals and sit near zero, empty templates with labels but no values, or "
                + "unlabelled side calculations that no table uses.");
        OPTIONS.put("orphan", "A few stray numeric cells with no label, no table around them and no relationship to "
                + "any schedule.");
    }

    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    OpenRouterRegionTriageClient(String apiKey, String model) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Opinion decide(String stateJson) throws Exception {
        long start = System.nanoTime();
        long activityId = LlmActivity.GLOBAL.begin(model);
        boolean succeeded = false;
        try {
            Opinion opinion = send(stateJson, start);
            succeeded = true;
            return opinion;
        } catch (Exception e) {
            LlmStats.GLOBAL.recordFailedCall(model, (System.nanoTime() - start) / 1_000_000);
            throw e;
        } finally {
            LlmActivity.GLOBAL.end(activityId, succeeded);
        }
    }

    private Opinion send(String stateJson, long start) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(OpenRouterDecisionClient.URL))
                .timeout(DEADLINE)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://github.com/seemantshankar/resurgent-ai-tev")
                .header("X-OpenRouter-Title", "TEV Parser")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(model, stateJson)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("region triage HTTP " + response.statusCode() + ": "
                    + response.body().substring(0, Math.min(200, response.body().length())));
        }
        JsonNode usage = MAPPER.readTree(response.body()).path("usage");
        Opinion opinion = parse(response.body());
        LlmStats.GLOBAL.recordCall(model, usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0),
                usage.path("cost").isNumber() ? usage.path("cost").asDouble() : null,
                (System.nanoTime() - start) / 1_000_000);
        return opinion;
    }

    static String requestBody(String model, String stateJson) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.set("state", MAPPER.readTree(stateJson));
        ObjectNode question = root.putObject("questions").putObject("triage");
        question.put("type", "choice");
        question.put("instructions",
                "Is this region of a financial-model worksheet part of what the sheet represents (main), the "
                        + "author's own working or check area (scratch), or stray cells (orphan)? Decide from what the "
                        + "cells are called, what their formulas do, and whether they read like content a reader of "
                        + "the sheet would look for. When unsure between main and scratch, choose main.");
        ObjectNode criteria = question.putObject("criteria");
        OPTIONS.forEach(criteria::put);
        return MAPPER.writeValueAsString(root);
    }

    static Opinion parse(String body) throws Exception {
        JsonNode triage = MAPPER.readTree(body).path("answers").path("triage");
        if (!triage.hasNonNull("choice")) {
            throw new IllegalStateException("region triage response lacks a choice");
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        triage.path("probabilities").fields()
                .forEachRemaining(e -> probabilities.put(e.getKey(), e.getValue().asDouble(0.0)));
        return new Opinion(
                triage.get("choice").asText().trim().toLowerCase(Locale.ROOT),
                triage.path("confidence").asDouble(0.0),
                probabilities);
    }
}
