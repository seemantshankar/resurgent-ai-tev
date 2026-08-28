package com.resurgent.tev.parser.redact;

/** One literal cell replacement recorded during redaction. */
public record RedactedCell(
        String sheetName,
        String coord,
        String original,
        String redacted,
        String kind) {
}
