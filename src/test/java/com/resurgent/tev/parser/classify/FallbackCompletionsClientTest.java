package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.classify.FallbackCompletionsClient.Link;
import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.CompletionResult;
import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.CompletionsClient;
import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm.UsageTotals;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FallbackCompletionsClientTest {

    @TempDir
    Path dir;

    /** Fails a request when {@code failIf} says so; records every user prompt it was sent. */
    static final class FakeClient implements CompletionsClient {
        final String name;
        final Predicate<String> failIf;
        final List<String> received = new ArrayList<>();

        FakeClient(String name, Predicate<String> failIf) {
            this.name = name;
            this.failIf = failIf;
        }

        @Override
        public CompletionResult completeJson(String system, String user, int max) {
            received.add(user);
            if (failIf.test(user)) {
                throw new IllegalStateException("HTTP 503 from " + name);
            }
            return new CompletionResult(name + ":" + user, null, null, null, "stop", false);
        }

        @Override
        public CompletionResult completeLayerA(String system, String user, List<String> families) {
            return completeJson(system, user, 0);
        }

        @Override
        public UsageTotals usageTotals() {
            return new UsageTotals(received.size(), 10L * received.size(), 1, 0.01 * received.size(), 0);
        }
    }

    private static FakeClient ok(String n) { return new FakeClient(n, u -> false); }
    private static FakeClient down(String n) { return new FakeClient(n, u -> true); }

    private static FallbackCompletionsClient chain(AtomicLong clock, FakeClient... clients) {
        List<Link> links = new ArrayList<>();
        for (FakeClient c : clients) {
            links.add(new Link(c.name, c));
        }
        return new FallbackCompletionsClient(links, clock::get);
    }

    @Test
    void firstModelAnswersWhenHealthy() {
        var m1 = ok("m1");
        var m3 = ok("m3");
        var out = chain(new AtomicLong(), m1, m3).completeJson("s", "req", 10);

        assertThat(out.content()).isEqualTo("m1:req");
        assertThat(m3.received).isEmpty();
    }

    @Test
    void onlyTheFailedRequestIsResentToTheNextModel() {
        var m1 = new FakeClient("m1", u -> u.equals("bad"));
        var m3 = ok("m3");
        var client = chain(new AtomicLong(), m1, m3);

        assertThat(client.completeJson("s", "a", 10).content()).isEqualTo("m1:a");
        assertThat(client.completeJson("s", "bad", 10).content()).isEqualTo("m3:bad");
        assertThat(client.completeJson("s", "b", 10).content()).isEqualTo("m1:b");

        assertThat(m3.received).containsExactly("bad"); // never a or b
    }

    @Test
    void chainRunsInOrderUntilAModelAnswers() {
        var m1 = down("m1");
        var m3 = down("m3");
        var m2 = ok("m2");

        assertThat(chain(new AtomicLong(), m1, m3, m2).completeJson("s", "req", 10).content())
                .isEqualTo("m2:req");
        assertThat(m1.received).hasSize(1);
        assertThat(m3.received).hasSize(1);
    }

    @Test
    void layerACallsFallBackToo() {
        var out = chain(new AtomicLong(), down("m1"), ok("m3")).completeLayerA("s", "req", List.of("assets"));

        assertThat(out.content()).isEqualTo("m3:req");
    }

    @Test
    void failsOnlyWhenEveryModelFailsAndSaysWhy() {
        assertThatThrownBy(() -> chain(new AtomicLong(), down("m1"), down("m3")).completeJson("s", "req", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all models failed")
                .hasMessageContaining("m1")
                .hasMessageContaining("m3");
    }

    @Test
    void aModelThatKeepsFailingIsSkippedThenRetriedAfterTheCoolOff() {
        var clock = new AtomicLong(1);
        var m1 = down("m1");
        var m3 = ok("m3");
        var client = chain(clock, m1, m3);

        for (int i = 0; i < 3; i++) {
            client.completeJson("s", "r" + i, 10);
        }
        assertThat(m1.received).hasSize(3);

        client.completeJson("s", "after", 10); // m1 is cooling off: not asked again
        assertThat(m1.received).hasSize(3);

        clock.addAndGet(FallbackCompletionsClient.COOL_OFF_NANOS + 1);
        client.completeJson("s", "later", 10);
        assertThat(m1.received).hasSize(4);
    }

    @Test
    void ifEveryModelIsCoolingOffTheyAreStillTried() {
        var clock = new AtomicLong(1);
        var m1 = new FakeClient("m1", u -> u.startsWith("fail"));
        var client = chain(clock, m1);
        for (int i = 0; i < 3; i++) {
            try {
                client.completeJson("s", "fail" + i, 10);
            } catch (IllegalStateException expected) {
                // counted toward the cool-off
            }
        }

        assertThat(client.completeJson("s", "recovered", 10).content()).isEqualTo("m1:recovered");
    }

    @Test
    void usageIsSummedAcrossTheChain() {
        var m1 = down("m1");
        var m3 = ok("m3");
        var client = chain(new AtomicLong(), m1, m3);
        client.completeJson("s", "a", 10);

        assertThat(client.usageTotals().calls()).isEqualTo(2);
    }

    @Test
    void emptyChainIsRejected() {
        assertThatThrownBy(() -> new FallbackCompletionsClient(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void environmentOrdersModelsOneThenThreeThenTwoAndSkipsBlanksAndDuplicates() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                // creds
                OPENROUTER_API_KEY=k
                Excel_Enrichment_Model_id=inception/mercury-2.5
                Excel_Enrichment_Model2_id=google/gemini-3.8-flash
                Excel_Enrichment_Model3_id=openai/gpt-6-luna
                """);
        assertThat(LlmEnvironment.modelChain(LlmEnvironment.load(env)))
                .containsExactly("inception/mercury-2.5", "openai/gpt-6-luna", "google/gemini-3.8-flash");

        Files.writeString(env, """
                Excel_Enrichment_Model_id=a
                Excel_Enrichment_Model2_id=a
                Excel_Enrichment_Model3_id=
                """);
        assertThat(LlmEnvironment.modelChain(LlmEnvironment.load(env))).containsExactly("a");
    }

    @Test
    void anUnparseableAnswerMovesTheRequestToTheNextModel() {
        var m1 = ok("m1");
        var m3 = ok("m3");
        var client = chain(new AtomicLong(), m1, m3);

        String parsed = client.perModel(c -> {
            String content = c.completeJson("s", "req", 10).content();
            if (content.startsWith("m1:")) {
                throw new IllegalArgumentException("not the JSON shape we need");
            }
            return content;
        });

        assertThat(parsed).isEqualTo("m3:req");
        assertThat(m1.received).containsExactly("req");
        assertThat(m3.received).containsExactly("req");
    }

    @Test
    void singleModelClientRunsTheOperationDirectly() {
        var only = ok("only");

        String out = only.perModel(c -> c.completeJson("s", "x", 1).content());

        assertThat(out).isEqualTo("only:x");
    }
}
