package com.resurgent.tev.parser.ingest;

/**
 * Stable reason codes for rows written to {@code ingest_rejection}. These are part
 * of the external contract surfaced to analysts; their string values must not change.
 */
public enum RejectionReason {
    XLS_DISABLED("xls_disabled"),
    UNSUPPORTED_FORMAT("unsupported_format"),
    FILE_TOO_LARGE("file_too_large"),
    POLICY_LIMIT_EXCEEDED("policy_limit_exceeded"),
    INVALID_WORKBOOK("invalid_workbook"),
    MISSING_MANDATE("missing_mandate");

    private final String code;

    RejectionReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
