package com.resurgent.tev.parser.ingest;

import java.nio.file.Path;

/**
 * Supported intake file formats. The string values match the {@code file_type}
 * column in {@code source_file}.
 */
public enum FileType {
    FM_XLSX("fm_xlsx"),
    FM_XLS("fm_xls"),
    FM_CSV("fm_csv");

    private final String value;

    FileType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * Determine the file type from the path's extension.
     *
     * @return {@link #FM_XLSX} for {@code .xlsx} / {@code .xlsm};
     *         {@link #FM_XLS} for {@code .xls};
     *         {@link #FM_CSV} for {@code .csv} / {@code .tsv}
     * @throws IllegalArgumentException for unsupported extensions
     */
    public static FileType fromPath(Path path) {
        String lower = path.getFileName().toString().toLowerCase();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
            return FM_XLSX;
        }
        if (lower.endsWith(".xls")) {
            return FM_XLS;
        }
        if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
            return FM_CSV;
        }
        throw new IllegalArgumentException(
                "unsupported file extension: " + path.getFileName());
    }
}
