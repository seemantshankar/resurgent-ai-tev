package com.resurgent.tev.parser.ingest;

/**
 * Detected CSV dialect: encoding, delimiter, BOM state and the reason the
 * dialect was chosen. Immutable value object produced by {@link CsvSniffer}
 * and consumed by {@link CsvAdapter}.
 */
public record CsvDialect(String encoding, char delimiter, boolean hasBom, String detectedBy) {
}
