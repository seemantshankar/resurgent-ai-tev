package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.nomenclature.IndustryResolution;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenRouterClassifierLlmTest {

    @Test
    void usesCompletionsClientAndParsesLayerA() {
        Packet packet = new Packet(1L, 1L, 1L, "child", List.of(
                new PacketCell(1L, 1L, "A1", 1, 1, PacketCell.ROLE_CORE, "string",
                        "Civil Works", "Civil Works", null, null, false, false)),
                List.of(), true);
        OntologySlice slice = new OntologySlice(
                IndustryResolution.confirmed("hotel"),
                List.of(new NomenclatureNode(
                        "Project Cost", "Project Cost", null,
                        NomenclatureNode.LAYER_SPINE, true, false, null, null)),
                List.of());
        LayerAPrompt prompt = new LayerAPrompt(packet, slice, null, false);

        OpenRouterClassifierLlm llm = new OpenRouterClassifierLlm(
                (system, user) -> """
                        {"scheduleFamily":"capex_detail","triage":"main","relevance":"primary",
                         "rowLabels":["Civil Works"],"columnHeaders":[],"packetDefaultHead":"Project Cost"}
                        """);
        LayerAJudgment judgment = llm.classifyLayerA(prompt);
        assertThat(judgment.scheduleFamily()).isEqualTo(ScheduleFamily.CAPEX_DETAIL);
        assertThat(judgment.packetDefaultHead()).isEqualTo("Project Cost");
        assertThat(LayerAPromptAssembler.userMessage(prompt)).contains("Civil Works");
        assertThat(LayerAPromptAssembler.userMessage(prompt)).contains("Project Cost");
    }

    @Test
    void retriesOnceWhenFirstCompletionIsNotJson() {
        AtomicInteger calls = new AtomicInteger();
        OpenRouterClassifierLlm llm = new OpenRouterClassifierLlm((system, user) -> {
            if (calls.incrementAndGet() == 1) {
                return "thinking...";
            }
            return "{\"scheduleFamily\":\"assumptions\",\"triage\":\"scratch\",\"relevance\":\"noise\"}";
        });
        Packet packet = new Packet(1L, 1L, 1L, "coverage_parent", List.of(), List.of(), true);
        OntologySlice slice = new OntologySlice(
                IndustryResolution.unspecified(), List.of(), List.of());
        LayerAJudgment judgment = llm.classifyLayerA(new LayerAPrompt(packet, slice, null, true));
        assertThat(calls.get()).isEqualTo(2);
        assertThat(judgment.scheduleFamily()).isEqualTo(ScheduleFamily.ASSUMPTIONS);
        assertThat(judgment.triage()).isEqualTo(Triage.SCRATCH);
    }

    @Test
    void retriesWhenTriageIsNotInEnum() {
        AtomicInteger calls = new AtomicInteger();
        OpenRouterClassifierLlm llm = new OpenRouterClassifierLlm((system, user) -> {
            if (calls.incrementAndGet() == 1) {
                return "{\"scheduleFamily\":\"capex_detail\",\"triage\":\"maybe\",\"relevance\":\"primary\"}";
            }
            return "{\"scheduleFamily\":\"capex_detail\",\"triage\":\"orphan\",\"relevance\":\"noise\"}";
        });
        Packet packet = new Packet(1L, 1L, 1L, "child", List.of(), List.of(), true);
        OntologySlice slice = new OntologySlice(
                IndustryResolution.unspecified(), List.of(), List.of());
        LayerAJudgment judgment = llm.classifyLayerA(new LayerAPrompt(packet, slice, null, false));
        assertThat(calls.get()).isEqualTo(2);
        assertThat(judgment.triage()).isEqualTo(Triage.ORPHAN);
    }

    @Test
    void extractsMessageContentFromOpenRouterEnvelope() throws Exception {
        String content = OpenRouterClassifierLlm.HttpCompletionsClient.content("""
                {"choices":[{"message":{"content":"{\\"ok\\":true}"}}]}
                """);
        assertThat(content).isEqualTo("{\"ok\":true}");
    }

    @Test
    void requestFollowsGlmFlashLatestStructuredOutputContract() throws Exception {
        String json = OpenRouterClassifierLlm.HttpCompletionsClient.requestBody(
                "~z-ai/glm-flash-latest",
                "system",
                "user");
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.path("model").asText()).isEqualTo("~z-ai/glm-flash-latest");
        assertThat(root.path("reasoning").path("effort").asText()).isEqualTo("low");
        assertThat(root.path("reasoning").path("max_tokens").isMissingNode()).isTrue();
        assertThat(root.path("max_tokens").asInt())
                .isEqualTo(OpenRouterClassifierLlm.LAYER_A_MAX_COMPLETION_TOKENS);
        assertThat(root.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(root.path("response_format").path("json_schema").path("schema")
                .path("properties").path("rowLabels").path("maxItems").asInt())
                .isEqualTo(OpenRouterClassifierLlm.LAYER_A_LABEL_MAX_ITEMS);
        assertThat(root.path("response_format").path("json_schema").path("strict").asBoolean())
                .isTrue();
        assertThat(root.path("response_format").path("json_schema").path("schema")
                .path("properties").path("triage").path("enum").toString())
                .contains("main")
                .contains("scratch")
                .contains("orphan");
        assertThat(root.path("provider").path("require_parameters").asBoolean()).isTrue();
        assertThat(root.path("provider").path("data_collection").asText()).isEqualTo("deny");
        assertThat(root.path("plugins").toString()).contains("response-healing");
        assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(root.path("messages").get(1).path("content").asText()).isEqualTo("user");
    }

    @Test
    void layerBRequestSchemaRequiresLines() throws Exception {
        String json = OpenRouterClassifierLlm.HttpCompletionsClient.requestBody(
                "~z-ai/glm-flash-latest",
                "system",
                "user",
                OpenRouterClassifierLlm.HttpCompletionsClient.layerBResponseFormat());
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.path("response_format").path("json_schema").path("name").asText())
                .isEqualTo("layer_b_bindings");
        JsonNode schema = root.path("response_format").path("json_schema").path("schema");
        assertThat(schema.path("properties").path("lines").path("items").path("type").asText())
                .isEqualTo("array");
        assertThat(schema.path("properties").path("soft").path("type").asText())
                .isEqualTo("array");
        assertThat(schema.path("required").toString()).contains("lines").contains("soft");
        assertThat(root.path("provider").path("data_collection").asText()).isEqualTo("deny");
        assertThat(root.path("reasoning").path("effort").asText()).isEqualTo("low");
        assertThat(root.path("max_tokens").asInt())
                .isEqualTo(OpenRouterClassifierLlm.LAYER_B_MIN_COMPLETION_TOKENS);
        assertThat(root.path("reasoning").path("max_tokens").isMissingNode()).isTrue();
    }

    @Test
    void layerBMaxCompletionTokensScalesWithCandidateLinesWithoutBlunt800Cap() {
        assertThat(OpenRouterClassifierLlm.layerBMaxCompletionTokens(10))
                .isEqualTo(OpenRouterClassifierLlm.LAYER_B_MIN_COMPLETION_TOKENS);
        assertThat(OpenRouterClassifierLlm.layerBMaxCompletionTokens(100))
                .isGreaterThan(800)
                .isEqualTo(100 * OpenRouterClassifierLlm.LAYER_B_TOKENS_PER_CANDIDATE_LINE
                        + OpenRouterClassifierLlm.LAYER_B_SOFT_BUDGET_TOKENS);
        assertThat(OpenRouterClassifierLlm.layerBMaxCompletionTokens(10_000))
                .isEqualTo(OpenRouterClassifierLlm.LAYER_B_MAX_COMPLETION_TOKENS);
    }

    @Test
    void contentWithUsageCapturesFinishReasonReasoningAndTruncation() throws Exception {
        OpenRouterClassifierLlm.CompletionResult result =
                OpenRouterClassifierLlm.HttpCompletionsClient.contentWithUsage(
                        "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":"
                                + "\"{\\\"lines\\\":[]}\"}}],"
                                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":900,"
                                + "\"completion_tokens_details\":{\"reasoning_tokens\":700}}}",
                        3);
        assertThat(result.truncated()).isTrue();
        assertThat(result.finishReason()).isEqualTo("length");
        assertThat(result.reasoningTokens()).isEqualTo(700);
        assertThat(result.completionTokens()).isEqualTo(900);
        assertThat(result.httpAttempts()).isEqualTo(3);
        assertThat(result.content()).contains("lines");
    }

    @Test
    void emptyContentWithFinishReasonLengthIsTruncated() throws Exception {
        OpenRouterClassifierLlm.CompletionResult result =
                OpenRouterClassifierLlm.HttpCompletionsClient.contentWithUsage(
                        "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":null}}],"
                                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":512}}",
                        1);
        assertThat(result.truncated()).isTrue();
        assertThat(result.finishReason()).isEqualTo("length");
        assertThat(result.content()).isEqualTo("{}");
    }

    @Test
    void rejectsTruncatedLayerACompletion() {
        OpenRouterClassifierLlm.HttpCompletionsClient client =
                new OpenRouterClassifierLlm.HttpCompletionsClient(
                        "key",
                        "model",
                        OpenRouterClassifierLlm.DEFAULT_URL,
                        body -> new OpenRouterClassifierLlm.ExchangeResponse(
                                200,
                                "{\"choices\":[{\"finish_reason\":\"length\","
                                        + "\"message\":{\"content\":null}}]}",
                                Optional.empty()),
                        delay -> {
                            throw new AssertionError("no sleep");
                        });
        OpenRouterClassifierLlm llm = new OpenRouterClassifierLlm(client);
        Packet packet = new Packet(1L, 1L, 1L, "child", List.of(), List.of(), true);
        OntologySlice slice = new OntologySlice(
                IndustryResolution.unspecified(), List.of(), List.of());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> llm.classifyLayerA(new LayerAPrompt(packet, slice, null, false)));
    }

    @Test
    void classifyLayerBParsesLinesFromCompletionsClient() {
        Packet packet = new Packet(1L, 1L, 1L, "child", List.of(
                new PacketCell(10L, 1L, "A2", 2, 1, PacketCell.ROLE_CORE, "string",
                        "Civil Works", "Civil Works", null, null, false, false),
                new PacketCell(1L, 1L, "B2", 2, 2, PacketCell.ROLE_CORE, "number",
                        null, "100", "100", null, false, false)),
                List.of(), true);
        OntologySlice slice = new OntologySlice(
                IndustryResolution.confirmed("hotel"),
                List.of(new NomenclatureNode(
                        "Project Cost > Civil Works > Structure", "Structure",
                        "Project Cost > Civil Works",
                        NomenclatureNode.LAYER_MANDATE_SOFT, false, true, null, 1L)),
                List.of());
        LayerAJudgment layerA = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                List.of(), List.of(), null);
        OpenRouterClassifierLlm llm = new OpenRouterClassifierLlm(
                (system, user) -> """
                        {"lines":[[0,0,0]],"soft":[]}
                        """);
        List<LayerBLineJudgment> lines = llm.classifyLayerB(
                new LayerBPrompt(packet, slice, layerA, null));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).coord()).isEqualTo("B2");
        assertThat(lines.get(0).verbatim()).isEqualTo("Civil Works");
        assertThat(lines.get(0).amountRole()).isEqualTo(AmountRole.ADD);
    }

    @Test
    void retriesTruncatedLayerBThenParsesLines() {
        Packet packet = new Packet(1L, 1L, 1L, "child", List.of(
                new PacketCell(10L, 1L, "A2", 2, 1, PacketCell.ROLE_CORE, "string",
                        "Civil Works", "Civil Works", null, null, false, false),
                new PacketCell(1L, 1L, "B2", 2, 2, PacketCell.ROLE_CORE, "number",
                        null, "100", "100", null, false, false)),
                List.of(), true);
        OntologySlice slice = new OntologySlice(
                IndustryResolution.confirmed("hotel"),
                List.of(new NomenclatureNode(
                        "Project Cost > Civil Works > Structure", "Structure",
                        "Project Cost > Civil Works",
                        NomenclatureNode.LAYER_MANDATE_SOFT, false, true, null, 1L)),
                List.of());
        LayerAJudgment layerA = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                List.of(), List.of(), null);
        AtomicInteger calls = new AtomicInteger();
        OpenRouterClassifierLlm.HttpCompletionsClient client =
                new OpenRouterClassifierLlm.HttpCompletionsClient(
                        "key",
                        "model",
                        OpenRouterClassifierLlm.DEFAULT_URL,
                        body -> {
                            if (calls.incrementAndGet() == 1) {
                                return new OpenRouterClassifierLlm.ExchangeResponse(
                                        200,
                                        "{\"choices\":[{\"finish_reason\":\"length\","
                                                + "\"message\":{\"content\":\"{\\\"lines\\\":[\"}}]}",
                                        Optional.empty());
                            }
                            return new OpenRouterClassifierLlm.ExchangeResponse(
                                    200,
                                    "{\"choices\":[{\"finish_reason\":\"stop\","
                                            + "\"message\":{\"content\":\"{\\\"lines\\\":[[0,0,0]],"
                                            + "\\\"soft\\\":[]}\"}}]}",
                                    Optional.empty());
                        },
                        delay -> {
                            throw new AssertionError("no sleep");
                        });
        List<LayerBLineJudgment> lines = new OpenRouterClassifierLlm(client).classifyLayerB(
                new LayerBPrompt(packet, slice, layerA, null));
        assertThat(calls.get()).isEqualTo(2);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).coord()).isEqualTo("B2");
    }

    @Test
    void retriesTruncatedLayerAWithABiggerBudgetThenParses() {
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        OpenRouterClassifierLlm.HttpCompletionsClient client =
                new OpenRouterClassifierLlm.HttpCompletionsClient(
                        "key",
                        "model",
                        OpenRouterClassifierLlm.DEFAULT_URL,
                        body -> {
                            bodies.add(body);
                            if (calls.incrementAndGet() == 1) {
                                return new OpenRouterClassifierLlm.ExchangeResponse(
                                        200,
                                        "{\"choices\":[{\"finish_reason\":\"length\","
                                                + "\"message\":{\"content\":null}}]}",
                                        Optional.empty());
                            }
                            return new OpenRouterClassifierLlm.ExchangeResponse(
                                    200,
                                    "{\"choices\":[{\"finish_reason\":\"stop\","
                                            + "\"message\":{\"content\":"
                                            + "\"{\\\"scheduleFamily\\\":\\\"capex_detail\\\","
                                            + "\\\"triage\\\":\\\"main\\\","
                                            + "\\\"relevance\\\":\\\"primary\\\","
                                            + "\\\"rowLabels\\\":[],\\\"columnHeaders\\\":[],"
                                            + "\\\"packetDefaultHead\\\":null}\"}}]}",
                                    Optional.empty());
                        },
                        delay -> {
                            throw new AssertionError("no sleep");
                        });
        Packet packet = new Packet(1L, 1L, 1L, "child", List.of(), List.of(), true);
        OntologySlice slice = new OntologySlice(
                IndustryResolution.unspecified(), List.of(), List.of());

        LayerAJudgment judgment = new OpenRouterClassifierLlm(client)
                .classifyLayerA(new LayerAPrompt(packet, slice, null, false));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(judgment.triage()).isEqualTo(Triage.MAIN);
        assertThat(bodies.get(0)).contains(
                "\"max_tokens\":" + OpenRouterClassifierLlm.LAYER_A_MAX_COMPLETION_TOKENS);
        assertThat(bodies.get(1))
                .as("a truncated Layer A retry needs room for the answer")
                .contains("\"max_tokens\":"
                        + OpenRouterClassifierLlm.LAYER_A_RETRY_MAX_COMPLETION_TOKENS);
    }

    @Test
    void retriesHttp429UsingRetryAfterThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> sleeps = new ArrayList<>();
        OpenRouterClassifierLlm.HttpCompletionsClient client =
                new OpenRouterClassifierLlm.HttpCompletionsClient(
                        "key",
                        "model",
                        OpenRouterClassifierLlm.DEFAULT_URL,
                        body -> {
                            if (calls.incrementAndGet() == 1) {
                                return new OpenRouterClassifierLlm.ExchangeResponse(
                                        429,
                                        "{\"error\":\"rate limited\"}",
                                        Optional.of("2"));
                            }
                            return new OpenRouterClassifierLlm.ExchangeResponse(
                                    200,
                                    "{\"choices\":[{\"message\":{\"content\":"
                                            + "\"{\\\"scheduleFamily\\\":\\\"assumptions\\\","
                                            + "\\\"triage\\\":\\\"main\\\","
                                            + "\\\"relevance\\\":\\\"supporting\\\","
                                            + "\\\"rowLabels\\\":[],\\\"columnHeaders\\\":[],"
                                            + "\\\"packetDefaultHead\\\":null}\"}}]}",
                                    Optional.empty());
                        },
                        sleeps::add);

        String content = client.complete("system", "user");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
        assertThat(content).contains("assumptions");
    }

    @Test
    void failsNon429HttpErrorsWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        OpenRouterClassifierLlm.HttpCompletionsClient client =
                new OpenRouterClassifierLlm.HttpCompletionsClient(
                        "key",
                        "model",
                        OpenRouterClassifierLlm.DEFAULT_URL,
                        body -> {
                            calls.incrementAndGet();
                            return new OpenRouterClassifierLlm.ExchangeResponse(
                                    500, "boom", Optional.empty());
                        },
                        delay -> {
                            throw new AssertionError("should not sleep for non-429");
                        });

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> client.complete("system", "user"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void retriesSocketTimeoutThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> sleeps = new ArrayList<>();
        OpenRouterClassifierLlm.HttpCompletionsClient client =
                new OpenRouterClassifierLlm.HttpCompletionsClient(
                        "key",
                        "model",
                        OpenRouterClassifierLlm.DEFAULT_URL,
                        body -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new java.io.IOException("Operation timed out");
                            }
                            return new OpenRouterClassifierLlm.ExchangeResponse(
                                    200,
                                    "{\"choices\":[{\"message\":{\"content\":"
                                            + "\"{\\\"scheduleFamily\\\":\\\"assumptions\\\","
                                            + "\\\"triage\\\":\\\"main\\\","
                                            + "\\\"relevance\\\":\\\"supporting\\\","
                                            + "\\\"rowLabels\\\":[],\\\"columnHeaders\\\":[],"
                                            + "\\\"packetDefaultHead\\\":null}\"}}]}",
                                    Optional.empty());
                        },
                        sleeps::add);

        String content = client.complete("system", "user");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(sleeps).isNotEmpty();
        assertThat(content).contains("assumptions");
    }

    @Test
    void doesNotRetryInterruptedHttpSend() {
        AtomicInteger calls = new AtomicInteger();
        OpenRouterClassifierLlm.HttpCompletionsClient client =
                new OpenRouterClassifierLlm.HttpCompletionsClient(
                        "key",
                        "model",
                        OpenRouterClassifierLlm.DEFAULT_URL,
                        body -> {
                            calls.incrementAndGet();
                            throw new InterruptedException("classify cancelled");
                        },
                        delay -> {
                            throw new AssertionError("should not sleep after interrupt");
                        });

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> client.complete("system", "user"));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void retryDelayFallsBackToCappedExponentialBackoff() {
        assertThat(OpenRouterClassifierLlm.HttpCompletionsClient.retryDelay(Optional.empty(), 1))
                .isEqualTo(Duration.ofMillis(500));
        assertThat(OpenRouterClassifierLlm.HttpCompletionsClient.retryDelay(Optional.empty(), 3))
                .isEqualTo(Duration.ofMillis(2000));
        assertThat(OpenRouterClassifierLlm.HttpCompletionsClient.retryDelay(Optional.of("999"), 1))
                .isEqualTo(OpenRouterClassifierLlm.HttpCompletionsClient.MAX_BACKOFF);
    }
}
