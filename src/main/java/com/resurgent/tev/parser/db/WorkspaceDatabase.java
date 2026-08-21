package com.resurgent.tev.parser.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Opens (creating if needed) a SQLite workspace database and applies any pending
 * versioned migration scripts bundled in the JAR under {@code db/migration}.
 */
public final class WorkspaceDatabase implements AutoCloseable {

    /** Bundled migration scripts in apply order; version = index + 1. */
    private static final String[] MIGRATIONS = {
            "db/migration/V1__initial_schema.sql",
            "db/migration/V2__source_file_raw_metadata.sql",
            "db/migration/V3__sprint1_schema.sql",
            "db/migration/V4__xlsx_cell_contract.sql",
            "db/migration/V5__cell_header_labels.sql",
            "db/migration/V6__structural_and_external_refs.sql",
            "db/migration/V7__audit_log_nullable_parse_run.sql"
    };

    private final Connection connection;

    private WorkspaceDatabase(Connection connection) {
        this.connection = connection;
    }

    public static WorkspaceDatabase open(java.nio.file.Path dbPath) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not on classpath", e);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
        }
        migrate(connection);
        return new WorkspaceDatabase(connection);
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private static void migrate(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_migration ("
                    + "version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
        }
        for (int i = 0; i < MIGRATIONS.length; i++) {
            int version = i + 1;
            if (isApplied(connection, version)) {
                continue;
            }
            apply(connection, version, MIGRATIONS[i]);
        }
    }

    private static boolean isApplied(Connection connection, int version) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM schema_migration WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void apply(Connection connection, int version, String resource) throws SQLException {
        String sql = readResource(resource);
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement s = connection.createStatement()) {
            for (String statement : sql.split(";\\s*\\R")) {
                String trimmed = statement.replaceAll("(?m)^--.*$", "").trim();
                if (!trimmed.isEmpty()) {
                    s.execute(trimmed);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO schema_migration (version, applied_at) VALUES (?, ?)")) {
                ps.setInt(1, version);
                ps.setString(2, Timestamps.now());
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static String readResource(String resource) throws SQLException {
        try (InputStream in = WorkspaceDatabase.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new SQLException("migration resource missing from JAR: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("failed reading migration resource: " + resource, e);
        }
    }
}
