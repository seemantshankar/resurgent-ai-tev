package com.resurgent.tev.doc.embed;

import java.net.URI;

/**
 * The HTTP boundary of {@link HttpEmbeddingClient}. Tests supply a stub; production
 * uses {@link JdkEmbeddingTransport}. Domain code never sees this.
 */
public interface EmbeddingTransport {

    /**
     * POSTs a JSON body and returns the response body.
     *
     * @param uri embeddings endpoint
     * @param bearer token without the {@code Bearer } prefix; may be blank
     * @param jsonBody request JSON
     * @param timeoutMs per-call deadline
     * @return response body, never null
     */
    String post(URI uri, String bearer, String jsonBody, long timeoutMs);
}
