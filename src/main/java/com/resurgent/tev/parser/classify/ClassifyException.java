package com.resurgent.tev.parser.classify;

/** Domain rejection for {@code tev-parse classify}. */
public class ClassifyException extends Exception {

    public ClassifyException(String message) {
        super(message);
    }

    public ClassifyException(String message, Throwable cause) {
        super(message, cause);
    }
}
