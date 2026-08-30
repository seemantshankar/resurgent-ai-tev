package com.resurgent.tev.parser.enrichment;

import java.io.IOException;

/** Indicates JSON that does not conform to the enrichment report v1 contract. */
public final class EnrichmentReportFormatException extends IOException {

    public EnrichmentReportFormatException(String message) {
        super(message);
    }

    public EnrichmentReportFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
