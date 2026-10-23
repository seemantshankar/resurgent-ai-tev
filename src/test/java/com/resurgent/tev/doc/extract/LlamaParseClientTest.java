package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HTTP-stub tests for {@link LlamaParseClient} (Parse v2 upload + poll). */
class LlamaParseClientTest {

    @TempDir
    Path temp;

    private HttpServer server;
    private String baseUrl;
    private String getResponse = """
            {"job":{"id":"pjb-1","status":"COMPLETED"},"markdown":{"pages":[{"page_number":1,"markdown":"# PPA"}]},"text":{"pages":[{"page_number":1,"text":"PPA"}]}}
            """;
    private final AtomicReference<String> lastConfig = new AtomicReference<>();
    private int pendingGets;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/parse", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/upload") && "POST".equals(ex.getRequestMethod())) {
                byte[] body = ex.getRequestBody().readAllBytes();
                String text = new String(body, StandardCharsets.UTF_8);
                int idx = text.indexOf("name=\"configuration\"");
                if (idx >= 0) {
                    int start = text.indexOf('{', idx);
                    int end = text.indexOf('}', start);
                    if (start >= 0 && end > start) {
                        lastConfig.set(text.substring(start, end + 1));
                    }
                }
                write(ex, 200, "{\"id\":\"pjb-1\",\"status\":\"PENDING\"}");
            } else if ("GET".equals(ex.getRequestMethod())) {
                if (pendingGets > 0) {
                    pendingGets--;
                    write(ex, 200, "{\"job\":{\"id\":\"pjb-1\",\"status\":\"RUNNING\"}}");
                } else {
                    write(ex, 200, getResponse);
                }
            } else {
                write(ex, 404, "{}");
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void write(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private LlamaParseClient client() {
        return new LlamaParseClient(Map.of(
                "LLAMA_CLOUD_API_KEY", "test-key",
                "LLAMA_EXTRACT_BASE_URL", baseUrl,
                "extract.timeout_ms", "5000"));
    }

    @Test
    void parsesFileAndReturnsPageMarkdown() throws Exception {
        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4");
        LlamaParseClient.ParseJob job = client().parse(pdf);
        assertThat(job.jobId()).isEqualTo("pjb-1");
        assertThat(job.status()).isEqualTo("COMPLETED");
        assertThat(job.pageMarkdown()).hasSize(1);
        assertThat(job.pageMarkdown().get(0).trim()).isEqualTo("# PPA");
        assertThat(job.markdown()).contains("PPA");
    }

    @Test
    void defaultsToCostEffectiveNotAgentic() throws Exception {
        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4");
        client().parse(pdf);
        assertThat(lastConfig.get()).contains("cost_effective");
        assertThat(lastConfig.get()).doesNotContain("agentic");
        assertThat(lastConfig.get()).contains("latest");
    }

    @Test
    void targetPagesAreSentOnlyForTheRequestedIndexes() throws Exception {
        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4");
        client().parse(pdf, ParseTier.FAST, List.of(2, 3, 5));
        assertThat(lastConfig.get()).contains("\"target_pages\":\"2-3,5\"");
    }

    @Test
    void explicitTierIsSent() throws Exception {
        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4");
        client().parse(pdf, ParseTier.FAST);
        assertThat(lastConfig.get()).contains("fast");
    }

    @Test
    void preservesPageIndexesAcrossIndependentMarkdownAndTextArrays() throws Exception {
        getResponse = """
                {"job":{"id":"pjb-1","status":"COMPLETED"},
                 "markdown":{"pages":[{"page_number":1,"markdown":"# Cover"},
                                      {"page_number":3,"markdown":"# Auditor Report"}]},
                 "text":{"pages":[{"page_number":1,"text":"Cover"},
                                  {"page_number":2,"text":"Middle page text"},
                                  {"page_number":3,"text":"Auditor Report"}]}}
                """;
        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4");

        LlamaParseClient.ParseJob job = client().parse(pdf);

        assertThat(job.pageMarkdown()).containsExactly(
                "# Cover\n", "Middle page text\n", "# Auditor Report\n");
        assertThat(job.pageText()).containsExactly("Cover", "Middle page text", "Auditor Report");
    }

    @Test
    void readsJobCreditsFromUsage() throws Exception {
        getResponse = """
                {"job":{"id":"pjb-1","status":"COMPLETED","usage":{"credits":30.0}},
                 "markdown":{"pages":[{"page_number":1,"markdown":"# PPA"}]}}
                """;
        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4");
        LlamaParseClient.ParseJob job = client().parse(pdf);
        assertThat(job.credits()).isEqualTo(30.0);
    }

    @Test
    void pollsUntilCompleted() throws Exception {
        pendingGets = 2;
        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4");
        LlamaParseClient.ParseJob job = new LlamaParseClient(Map.of(
                "LLAMA_CLOUD_API_KEY", "k",
                "LLAMA_EXTRACT_BASE_URL", baseUrl,
                "extract.timeout_ms", "10000"))
                .parse(pdf);
        assertThat(job.status()).isEqualTo("COMPLETED");
    }

    @Test
    void failedJobThrows() {
        getResponse = """
                {"job":{"id":"pjb-1","status":"FAILED","error_message":"unreadable scan"}}
                """;
        Path pdf = temp.resolve("doc.pdf");
        try {
            Files.writeString(pdf, "%PDF-1.4");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertThatThrownBy(() -> client().parse(pdf))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("FAILED")
                .hasMessageContaining("unreadable");
    }

    @Test
    void missingKeyThrows() throws Exception {
        Path pdf = temp.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4");
        assertThatThrownBy(() -> new LlamaParseClient(Map.of(
                "LLAMA_EXTRACT_BASE_URL", baseUrl)).parse(pdf))
                .isInstanceOf(ExtractException.class)
                .hasMessageContaining("API key");
    }
}
