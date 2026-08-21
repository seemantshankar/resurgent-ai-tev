package com.resurgent.tev.parser.ingest;

/**
 * Stable reason codes for rows written to {@code ingest_rejection}. These are part
 * of the external contract surfaced to analysts; their string values must not change.
 */
public enum RejectionReason {
    XLS_DISABLED("xls_disabled"),
    UNSUPPORTED_FORMAT("unsupported_format"),
    FILE_TOO_LARGE("file_too_large"),
    EXPANSION_RATIO_EXCEEDED("expansion_ratio_exceeded"),
    SHEET_COUNT_EXCEEDED("sheet_count_exceeded"),
    ROW_COUNT_EXCEEDED("row_count_exceeded"),
    COLUMN_COUNT_EXCEEDED("column_count_exceeded"),
    CELL_COUNT_EXCEEDED("cell_count_exceeded"),
    PASSWORD_PROTECTED("password_protected"),
    OLE_OBJECT_REJECTED("ole_object_rejected"),
    DDE_LINK_REJECTED("dde_link_rejected"),
    MALFORMED_PACKAGE("malformed_package"),
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
