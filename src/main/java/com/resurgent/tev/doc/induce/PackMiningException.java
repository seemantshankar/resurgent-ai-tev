package com.resurgent.tev.doc.induce;

/** Thrown when a {@link PackMiningClient} cannot mine a cluster. */
public class PackMiningException extends Exception {

    /**
     * @param message actionable reason (required)
     */
    public PackMiningException(String message) {
        super(message);
    }

    /**
     * @param message actionable reason (required)
     * @param cause   underlying error
     */
    public PackMiningException(String message, Throwable cause) {
        super(message, cause);
    }
}
