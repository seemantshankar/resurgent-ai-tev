package com.resurgent.tev.doc.embed;

/**
 * Vectorises clause and passage text. The default implementation is a local
 * n-gram hash that never opens a socket. An HTTP adapter is used only when an
 * embeddings endpoint is configured and the caller is not {@code local_only}.
 */
public interface EmbeddingClient {

    /** Model identifier stored beside the vector. */
    String model();

    /** Dimension count of {@link #embed(String)}. */
    int dims();

    /**
     * Embeds one text. Blank text is a zero vector. Non-blank local vectors are
     * L2-normalised {@code float32} unit vectors.
     *
     * @param text clause verbatim, passage text, or a free-text query
     * @return a new array; never null
     */
    float[] embed(String text);
}
