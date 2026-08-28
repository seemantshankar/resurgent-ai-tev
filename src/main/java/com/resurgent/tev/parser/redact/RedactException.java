package com.resurgent.tev.parser.redact;

/** Redaction failed because prerequisites were not met or inputs were invalid. */
public final class RedactException extends Exception {

    public RedactException(String message) {
        super(message);
    }
}
