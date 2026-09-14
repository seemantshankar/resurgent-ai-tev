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
        assertThat(root.path("response_format").path("type").asText()).isEqualTo("json_schema");
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
    void retryDelayFallsBackToCappedExponentialBackoff() {
        assertThat(OpenRouterClassifierLlm.HttpCompletionsClient.retryDelay(Optional.empty(), 1))
                .isEqualTo(Duration.ofMillis(500));
        assertThat(OpenRouterClassifierLlm.HttpCompletionsClient.retryDelay(Optional.empty(), 3))
                .isEqualTo(Duration.ofMillis(2000));
        assertThat(OpenRouterClassifierLlm.HttpCompletionsClient.retryDelay(Optional.of("999"), 1))
                .isEqualTo(OpenRouterClassifierLlm.HttpCompletionsClient.MAX_BACKOFF);
    }
}
