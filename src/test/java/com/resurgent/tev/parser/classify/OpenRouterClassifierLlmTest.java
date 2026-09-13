package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.nomenclature.IndustryResolution;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.List;
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
        assertThat(root.path("plugins").toString()).contains("response-healing");
        assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(root.path("messages").get(1).path("content").asText()).isEqualTo("user");
    }
}
