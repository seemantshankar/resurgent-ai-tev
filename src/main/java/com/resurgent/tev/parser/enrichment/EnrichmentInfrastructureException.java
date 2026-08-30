package com.resurgent.tev.parser.enrichment;

/** External-model, transport, or response parsing failure. */
public final class EnrichmentInfrastructureException extends Exception {

    public EnrichmentInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
