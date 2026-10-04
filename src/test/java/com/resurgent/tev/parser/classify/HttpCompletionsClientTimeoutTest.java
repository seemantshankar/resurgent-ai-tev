package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A reply that stalls part-way must fail within the total deadline, not hang the run. */
class HttpCompletionsClientTimeoutTest {

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(boolean sendHeadersFirst) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                if (sendHeadersFirst) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0); // chunked: body to follow
                    OutputStream body = exchange.getResponseBody();
                    body.write("{\"choices\":[".getBytes());
                    body.flush();
                }
                release.await(); // then the provider goes silent
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Test
    void replyThatStallsAfterTheHeadersFailsAtTheTotalDeadline() throws Exception {
        var client = new OpenRouterClassifierLlm.HttpCompletionsClient(
                "key", "m", serve(true), new OpenRouterClassifierLlm.Deadlines(Duration.ofSeconds(2), Duration.ofSeconds(2)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.completeJson("s", "u", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void replyThatNeverStartsFailsAtTheTotalDeadline() throws Exception {
        var client = new OpenRouterClassifierLlm.HttpCompletionsClient(
                "key", "m", serve(false), new OpenRouterClassifierLlm.Deadlines(Duration.ofSeconds(2), Duration.ofSeconds(2)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.completeJson("s", "u", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aStalledModelFallsBackToTheNextOne() throws Exception {
        var stalled = new OpenRouterClassifierLlm.HttpCompletionsClient(
                "key", "slow", serve(true), new OpenRouterClassifierLlm.Deadlines(Duration.ofSeconds(2), Duration.ofSeconds(2)));
        var healthy = new FallbackCompletionsClientTest.FakeClient("healthy", u -> false);
        var chain = new FallbackCompletionsClient(java.util.List.of(
                new FallbackCompletionsClient.Link("slow", stalled),
                new FallbackCompletionsClient.Link("healthy", healthy)));

        assertThat(chain.completeJson("s", "req", 10).content()).isEqualTo("healthy:req");
    }

    @Test
    void smallRequestsUseTheShortDeadlineAndLargeOnesTheLongOne() throws Exception {
        var client = new OpenRouterClassifierLlm.HttpCompletionsClient(
                "key", "m", serve(true),
                new OpenRouterClassifierLlm.Deadlines(Duration.ofSeconds(1), Duration.ofSeconds(4)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.completeJson("s", "tiny prompt", 4_096))
                .hasMessageContaining("timed out");
        Duration small = Duration.ofNanos(System.nanoTime() - start);

        start = System.nanoTime();
        assertThatThrownBy(() -> client.completeJson("s", "tiny prompt", 32_768))
                .hasMessageContaining("timed out");
        Duration large = Duration.ofNanos(System.nanoTime() - start);

        assertThat(small).isLessThan(Duration.ofMillis(3_000));
        assertThat(large).isGreaterThan(Duration.ofMillis(3_500));
    }

    @Test
    void deadlineClassifiesByCompletionBudgetAndPromptSize() {
        var d = new OpenRouterClassifierLlm.Deadlines(Duration.ofSeconds(45), Duration.ofSeconds(180));

        assertThat(d.forRequest(600, 4_096)).isEqualTo(Duration.ofSeconds(45));
        assertThat(d.forRequest(3_900, 2_048)).isEqualTo(Duration.ofSeconds(45));
        assertThat(d.forRequest(86_680, 4_096)).isEqualTo(Duration.ofSeconds(180));
        assertThat(d.forRequest(600, 32_768)).isEqualTo(Duration.ofSeconds(180));
    }

    @Test
    void onlyTheLastModelInTheChainGetsTheGenerousDeadlines() {
        var first = OpenRouterClassifierLlm.deadlinesFor(0, 3);
        var middle = OpenRouterClassifierLlm.deadlinesFor(1, 3);
        var last = OpenRouterClassifierLlm.deadlinesFor(2, 3);
        var only = OpenRouterClassifierLlm.deadlinesFor(0, 1);

        assertThat(first.small()).isEqualTo(Duration.ofSeconds(30));
        assertThat(middle).isEqualTo(first);
        assertThat(last.small()).isGreaterThan(first.small());
        assertThat(last.large()).isGreaterThan(first.large());
        assertThat(only).isEqualTo(last); // nothing to fall back to: be patient
    }
}
