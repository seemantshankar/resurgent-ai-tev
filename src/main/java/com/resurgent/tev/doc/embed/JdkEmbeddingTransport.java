package com.resurgent.tev.doc.embed;

import com.resurgent.tev.doc.residency.DataResidency;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JDK {@link HttpClient} transport. Mirrors {@code HttpDocLlmClient}: async send
 * with a hard timeout, bearer auth, JSON body. Tests never construct this.
 */
final class JdkEmbeddingTransport implements EmbeddingTransport {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    public String post(URI uri, String bearer, String jsonBody, long timeoutMs) {
        if (DataResidency.locked()) {
            throw new EmbeddingException(
                    "remote embeddings blocked: data residency is local_only");
        }
        long deadline = timeoutMs > 0 ? timeoutMs : 60_000L;
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(deadline))
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://github.com/seemantshankar/resurgent-ai-tev")
                .header("X-OpenRouter-Title", "TEV Document Intelligence")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        if (bearer != null && !bearer.isBlank()) {
            request.header("Authorization", "Bearer " + bearer);
        }
        CompletableFuture<HttpResponse<String>> pending =
                http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
        try {
            HttpResponse<String> response = pending.get(deadline, TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new EmbeddingException("embeddings HTTP " + response.statusCode() + ": "
                        + shorten(response.body()));
            }
            return response.body() == null ? "" : response.body();
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new EmbeddingException("embeddings call exceeded " + deadline + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof EmbeddingException embeddingException) {
                throw embeddingException;
            }
            throw new EmbeddingException("embeddings request failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new EmbeddingException("embeddings call interrupted", e);
        }
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "…";
    }
}
