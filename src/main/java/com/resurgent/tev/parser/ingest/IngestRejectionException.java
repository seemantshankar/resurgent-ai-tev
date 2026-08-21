package com.resurgent.tev.parser.ingest;

/**
 * A well-formed input was rejected before or during intake. Mapped to exit code 3.
 * Carries a stable reason code and the configured limit / observed value that led to
 * the rejection so the {@code ingest_rejection} row can be written durably.
 */
public class IngestRejectionException extends RuntimeException {

    private final RejectionReason reason;
    private final Object configuredLimit;
    private final Object observedValue;

    public IngestRejectionException(RejectionReason reason, Object configuredLimit,
            Object observedValue, String explanation) {
        super(explanation);
        this.reason = reason;
        this.configuredLimit = configuredLimit;
        this.observedValue = observedValue;
    }

    public RejectionReason reason() {
        return reason;
    }

    public Object configuredLimit() {
        return configuredLimit;
    }

    public Object observedValue() {
        return observedValue;
    }

    public String reasonCode() {
        return reason.code();
    }
}
