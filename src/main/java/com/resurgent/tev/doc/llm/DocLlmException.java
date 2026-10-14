package com.resurgent.tev.doc.llm;

/** Raised when a document-side LLM call cannot complete (HTTP, timeout, malformed payload). */
public class DocLlmException extends RuntimeException {

    /**
     * @param message actionable failure reason (required)
     */
    public DocLlmException(String message) {
        super(message);
    }

    /**
     * @param message actionable failure reason (required)
     * @param cause underlying failure
     */
    public DocLlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
