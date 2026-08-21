package com.resurgent.tev.parser.ingest;

import java.util.List;

/**
 * One external link discovered inside an XLSX/XLSM package.
 *
 * <p>{@code linkIndex} is the 1-based index used in formula external references
 * such as {@code [1]Sheet!A1}.
 */
public record ExternalLinkIn(
        Integer linkIndex,
        String targetUri,
        String targetDisplay,
        boolean refreshError,
        List<String> sheetNames,
        String rawPartName) {
}
