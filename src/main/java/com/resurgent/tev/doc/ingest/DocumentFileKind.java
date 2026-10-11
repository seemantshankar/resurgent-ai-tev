package com.resurgent.tev.doc.ingest;

import java.nio.file.Path;
import java.util.Locale;

/** Non-Excel document intake kinds for M1. */
public enum DocumentFileKind {
    PDF("doc_pdf", "agreement"),
    IMAGE("doc_image", "other"),
    UNKNOWN("doc_other", "other");

    private final String sourceFileType;
    private final String defaultFileRole;

    DocumentFileKind(String sourceFileType, String defaultFileRole) {
        this.sourceFileType = sourceFileType;
        this.defaultFileRole = defaultFileRole;
    }

    /** Value for {@code source_file.file_type} (existing CHECK-free column). */
    public String sourceFileType() {
        return sourceFileType;
    }

    public String defaultFileRole() {
        return defaultFileRole;
    }

    public static DocumentFileKind fromPath(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return PDF;
        }
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".tif") || name.endsWith(".tiff") || name.endsWith(".webp")) {
            return IMAGE;
        }
        return UNKNOWN;
    }

    public static String fileExt(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    /**
     * Controlled {@code document_version.file_role} vocabulary (Architecture §5.2).
     * Unknown values fall through as {@code other}.
     */
    public static String normalizeFileRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return "other";
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "fm_workbook", "dpr", "agreement", "certificate", "quotation",
                    "technical_report", "regulatory", "identity", "other" -> v;
            default -> "other";
        };
    }

    public static String defaultDocFamily(String fileRole) {
        return switch (fileRole) {
            case "agreement" -> "agreement";
            case "certificate", "identity" -> "certificate";
            case "dpr" -> "dpr";
            case "quotation" -> "quotation";
            case "technical_report" -> "technical_report";
            case "regulatory" -> "regulatory";
            default -> "other";
        };
    }
}
