package com.resurgent.tev.parser.classify;

/**
 * One LLM attempt outran its own budget while the run still has time. Distinct
 * from the run-level classify deadline: callers that can drop a single call's
 * work catch this and continue, where the classify deadline always aborts.
 */
final class AttemptDeadlineException extends ClassifyException {

    AttemptDeadlineException(String message, Throwable cause) {
        super(message, cause);
    }
}
