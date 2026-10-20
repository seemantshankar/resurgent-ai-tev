package com.resurgent.tev.doc.assess;

import java.util.Locale;

/**
 * Persisted outcome status of one assessment stage run (issue #151).
 * Corresponds to {@code status} column in {@code assessment_run}.
 */
public enum StageStatus {
    RUNNING("running"),
    COMPLETED("completed"),
    PARTIAL("partial"),
    FAILED("failed");

    private final String dbValue;

    StageStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static StageStatus fromDb(String raw) {
        if (raw == null) {
            return FAILED;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "running" -> RUNNING;
            case "completed" -> COMPLETED;
            case "partial" -> PARTIAL;
            case "failed" -> FAILED;
            default -> throw new IllegalArgumentException("Unknown stage status: " + raw);
        };
    }
}
