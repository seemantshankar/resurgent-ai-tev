package com.resurgent.tev.doc.extract;

/** Failure inside an extract adapter (timeout, 4xx, malformed payload). */
public class ExtractException extends Exception {

    /**
     * Creates an extract failure with a detail message.
     *
     * @param message human-readable failure reason
     */
    public ExtractException(String message) {
        super(message);
    }

    /**
     * Creates an extract failure with a detail message and cause.
     *
     * @param message human-readable failure reason
     * @param cause underlying error
     */
    public ExtractException(String message, Throwable cause) {
        super(message, cause);
    }
}
