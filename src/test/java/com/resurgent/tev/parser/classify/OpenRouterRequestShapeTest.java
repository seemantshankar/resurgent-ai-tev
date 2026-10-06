package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.HttpCompletionsClient;
import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.Route;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OpenRouterRequestShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode body(String model, Route route) throws Exception {
        return MAPPER.readTree(HttpCompletionsClient.requestBody(model, "system", "user", null, 1_000, route));
    }

    @Test
    void mimoOnOpenRouterIsAskedWithoutReasoningAndWithoutProviderPinning() throws Exception {
        JsonNode mimo = body("xiaomi/mimo-v2.6-flash", Route.OPENROUTER);
        assertThat(mimo.path("model").asText()).isEqualTo("xiaomi/mimo-v2.6-flash");
        assertThat(mimo.at("/reasoning/enabled").asBoolean(true)).isFalse();
        assertThat(mimo.at("/reasoning").has("effort")).isFalse();
        assertThat(mimo.at("/provider").has("order")).isFalse();
        assertThat(mimo.at("/provider/data_collection").asText()).isEqualTo("deny");
    }

    @Test
    void otherModelsKeepLowReasoningAndOpenRouterRouting() throws Exception {
        for (String model : new String[] {"openai/gpt-6-luna", "google/gemini-3.8-flash"}) {
            JsonNode other = body(model, Route.OPENROUTER);
            assertThat(other.at("/reasoning/effort").asText()).isEqualTo("low");
            assertThat(other.at("/provider").has("order")).isFalse();
            assertThat(other.at("/provider/data_collection").asText()).isEqualTo("deny");
        }
    }

    @Test
    void xiaomiRequestUsesXiaomisModelNameAndThinkingSwitch() throws Exception {
        JsonNode xiaomi = body("xiaomi/mimo-v2.6-flash", Route.XIAOMI);
        assertThat(xiaomi.path("model").asText()).isEqualTo("mimo-v2.6-flash");
        assertThat(xiaomi.at("/thinking/type").asText()).isEqualTo("disabled");
        assertThat(xiaomi.path("max_completion_tokens").asInt()).isEqualTo(1_000);
        assertThat(xiaomi.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(xiaomi.has("reasoning")).isFalse();
        assertThat(xiaomi.has("provider")).isFalse();
        assertThat(xiaomi.at("/messages/1/content").asText()).isEqualTo("user");
    }

    @Test
    void xiaomiCostIsWorkedOutFromItsPublishedPrices() {
        var client = new HttpCompletionsClient("k", "xiaomi/mimo-v2.6-flash", "http://localhost/x",
                OpenRouterClassifierLlm.Deadlines.LAST_RESORT, Route.XIAOMI);
        String reply = "{\"usage\":{\"prompt_tokens\":1000000,\"completion_tokens\":500000,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":200000}}}";
        // 800k uncached at $0.14/M + 200k cached at $0.0028/M + 500k out at $0.28/M
        assertThat(client.xiaomiCostUsd(reply)).isCloseTo(0.112 + 0.00056 + 0.14, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(client.xiaomiCostUsd("{\"usage\":{}}")).isNull();
        var unknown = new HttpCompletionsClient("k", "xiaomi/mimo-v9", "http://localhost/x",
                OpenRouterClassifierLlm.Deadlines.LAST_RESORT, Route.XIAOMI);
        assertThat(unknown.xiaomiCostUsd(reply)).isNull();
    }

    @Test
    void mimoGetsTimeToWriteItsAnswerEvenWhenItIsFirstInTheChain() {
        var first = OpenRouterClassifierLlm.deadlinesFor("xiaomi/mimo-v2.6-flash", 0, 3);
        assertThat(first.forRequest(5_000, 12_288)).isEqualTo(Duration.ofSeconds(45));
        assertThat(first.forRequest(300_000, 32_768)).isEqualTo(Duration.ofSeconds(150));
        // Other models keep the fail-fast limit that was measured for them.
        assertThat(OpenRouterClassifierLlm.deadlinesFor("openai/gpt-6-luna", 0, 3).forRequest(5_000, 12_288))
                .isEqualTo(Duration.ofSeconds(15));
        // A last resort is patient whatever the model.
        assertThat(OpenRouterClassifierLlm.deadlinesFor("xiaomi/mimo-v2.6-flash", 2, 3).forRequest(5_000, 12_288))
                .isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void xiaomiKeyChoosesTheRouteOnlyForMimo() {
        assertThat(OpenRouterClassifierLlm.isMimo("xiaomi/mimo-v2.6-flash")).isTrue();
        assertThat(OpenRouterClassifierLlm.isMimo("openai/gpt-6-luna")).isFalse();
    }
}
