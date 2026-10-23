package com.resurgent.tev.doc.embed;

/** Raised when a remote embedding call cannot complete. */
public class EmbeddingException extends RuntimeException {

    /**
     * @param message actionable failure reason (required)
     */
    public EmbeddingException(String message) {
        super(message);
    }

    /**
     * @param message actionable failure reason (required)
     * @param cause underlying failure
     */
    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
