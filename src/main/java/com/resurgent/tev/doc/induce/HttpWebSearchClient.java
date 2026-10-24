package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.residency.DataResidency;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * GET search against a configured endpoint. Failures return an empty snippet so
 * induction continues on the sanitized fingerprint alone. Tests inject a
 * {@link WebSearchClient} and never construct this.
 */
public final class HttpWebSearchClient implements WebSearchClient {

    private final String endpoint;
    private final String residency;
    private final HttpClient http;
    private final java.util.function.UnaryOperator<String> transport;

    /**
     * @param endpoint search origin; the sanitized query is sent as {@code q}
     */
    public HttpWebSearchClient(String endpoint) {
        this(endpoint, null, null);
    }

    /**
     * @param endpoint search origin
     * @param residency effective data residency; {@code local_only} does not search
     * @param transport test double, or null for the JDK client
     */
    public HttpWebSearchClient(String endpoint, String residency,
            java.util.function.UnaryOperator<String> transport) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.residency = residency;
        this.transport = transport;
        this.http = transport == null
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                : null;
    }

    @Override
    public String id() {
        return "http-web-search";
    }

    @Override
    public String search(String sanitizedQuery) {
        if (DataResidency.locked(residency) || DataResidency.isLocalOnly(residency)) {
            return "";
        }
        if (endpoint.isEmpty() || sanitizedQuery == null || sanitizedQuery.isBlank()) {
            return "";
        }
        if (transport != null) {
            String body = transport.apply(sanitizedQuery);
            return body == null ? "" : body;
        }
        String joiner = endpoint.contains("?") ? "&" : "?";
        String url = endpoint + joiner + "q="
                + URLEncoder.encode(sanitizedQuery, StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "text/plain")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 || response.body() == null) {
                return "";
            }
            String body = response.body().trim();
            return body.length() > 2_000 ? body.substring(0, 2_000) : body;
        } catch (Exception e) {
            return "";
        }
    }
}
