package com.resurgent.tev.parser.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
            "db/migration/V7__audit_log_nullable_parse_run.sql",
            "db/migration/V8__sprint2_schema.sql",
            "db/migration/V9__error_barriers.sql",
            "db/migration/V10__sprint3a_region_schema.sql",
            "db/migration/V11__sprint3b_schema.sql",
            "db/migration/V12__worksheet_role_reasons.sql"
    };

    private final Connection connection;

    private WorkspaceDatabase(Connection connection) {
        this.connection = connection;
    }

    public record OpenOptions(boolean destructiveResetAllowed, boolean failV11BeforeCommit) {
        public static OpenOptions defaults() {
            return new OpenOptions(false, false);
        }

        public static OpenOptions allowDestructiveReset() {
            return new OpenOptions(true, false);
        }

        static OpenOptions injectV11Failure() {
            return new OpenOptions(true, true);
        }
    }

    public static WorkspaceDatabase open(Path dbPath) throws SQLException {
        return open(dbPath, OpenOptions.defaults());
    }

    public static WorkspaceDatabase open(Path dbPath, OpenOptions options) throws SQLException {
        Objects.requireNonNull(dbPath, "dbPath");
        Objects.requireNonNull(options, "options");
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not on classpath", e);
        }
        Path absolute = dbPath.toAbsolutePath().normalize();
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        try {
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
            }
            migrate(connection, absolute, options);
            return new WorkspaceDatabase(connection);
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException close) {
                e.addSuppressed(close);
            }
            throw e;
        }
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private static void migrate(Connection connection, Path dbPath, OpenOptions options)
            throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_migration ("
                    + "version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
        }
        for (int i = 0; i < MIGRATIONS.length; i++) {
            int version = i + 1;
            if (isApplied(connection, version)) {
                continue;
            }
            if (version == 11 && isPopulated(connection) && !options.destructiveResetAllowed()) {
                throw new DestructiveResetRequiredException(dbPath);
            }
            apply(connection, version, MIGRATIONS[i], options.failV11BeforeCommit());
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

    private static boolean isPopulated(Connection connection) throws SQLException {
        List<String> tables = userTables(connection);
        for (String table : tables) {
            try (Statement s = connection.createStatement();
                    ResultSet rs = s.executeQuery("SELECT 1 FROM " + table + " LIMIT 1")) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> userTables(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' "
                                + "AND name NOT LIKE 'sqlite_%' AND name != 'schema_migration'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static void wipeOperationalData(Connection connection) throws SQLException {
        List<String> tables = userTables(connection);
        try (Statement s = connection.createStatement()) {
            for (String table : tables) {
                s.execute("DELETE FROM " + table);
            }
        }
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'")) {
            if (rs.next()) {
                try (Statement wipe = connection.createStatement()) {
                    wipe.execute("DELETE FROM sqlite_sequence");
                }
            }
        }
    }

    private static void apply(Connection connection, int version, String resource,
            boolean failV11BeforeCommit) throws SQLException {
        String sql = readResource(resource);
        boolean autoCommit = connection.getAutoCommit();
        // SQLite's documented ALTER TABLE procedure: foreign_keys must be toggled OFF
        // outside of any transaction (it is a silent no-op inside one), the migration
        // runs inside the transaction as usual, and a foreign_key_check is run just
        // before commit to surface any dangling references loudly instead of silently.
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA foreign_keys = OFF");
        }
        try {
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                if (version == 11) {
                    wipeOperationalData(connection);
                }
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
                try (Statement checkStmt = connection.createStatement();
                        ResultSet rs = checkStmt.executeQuery("PRAGMA foreign_key_check")) {
                    if (rs.next()) {
                        throw new SQLException("migration " + resource
                                + " left dangling foreign keys (PRAGMA foreign_key_check found violations)");
                    }
                }
                if (version == 11 && failV11BeforeCommit) {
                    throw new SQLException("injected V11 failure");
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } finally {
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
            }
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
