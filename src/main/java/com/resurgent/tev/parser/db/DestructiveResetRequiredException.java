package com.resurgent.tev.parser.db;

import java.nio.file.Path;
import java.sql.SQLException;

/**
 * Thrown when a populated pre-live workspace would be erased by V11 unless the
 * operator passes an explicit destructive-reset opt-in.
 */
public final class DestructiveResetRequiredException extends SQLException {

    private final Path databasePath;

    public DestructiveResetRequiredException(Path databasePath) {
        super(message(databasePath));
        this.databasePath = databasePath.toAbsolutePath().normalize();
    }

    public Path databasePath() {
        return databasePath;
    }

    private static String message(Path databasePath) {
        Path absolute = databasePath.toAbsolutePath().normalize();
        return "Refusing to apply the Sprint 3b schema reset to populated workspace "
                + absolute
                + ". This erases all parser-owned operational data. "
                + "Re-run with --allow-destructive-reset if that is intended.";
    }
}
