package com.resurgent.tev.doc.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The platform registry: mandate identity and the platform default spend ceiling.
 * Created only when a command is pointed at its path. It holds no client names,
 * facts, soft leaves, aliases, or open clause types.
 */
public final class PlatformRegistry implements AutoCloseable {

    /** A mandate row as the registry stores it. */
    public record MandateView(long mandateId, String workspacePath, String industry, String status) {}

    private final Connection connection;

    private PlatformRegistry(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens the registry, creating the file and its tables when the path is new.
     */
    public static PlatformRegistry open(Path path) throws SQLException {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
        } catch (IOException e) {
            throw new SQLException("cannot create platform registry directory " + absolute.getParent(), e);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not on classpath", e);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            migrate(connection);
            return new PlatformRegistry(connection);
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException close) {
                e.addSuppressed(close);
            }
            throw e;
        }
    }

    /**
     * Records the mandate. A null industry leaves an industry already stored.
     * Status stays open.
     */
    public void recordMandate(long mandateId, Path workspace, String industry) throws SQLException {
        String path = workspace.toAbsolutePath().normalize().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO mandate (mandate_id, workspace_path, industry, status)"
                        + " VALUES (?, ?, ?, 'open')"
                        + " ON CONFLICT(mandate_id) DO UPDATE SET"
                        + " workspace_path = excluded.workspace_path,"
                        + " industry = COALESCE(excluded.industry, mandate.industry),"
                        + " status = 'open'")) {
            ps.setLong(1, mandateId);
            ps.setString(2, path);
            if (industry == null || industry.isBlank()) {
                ps.setNull(3, java.sql.Types.VARCHAR);
            } else {
                ps.setString(3, industry);
            }
            ps.executeUpdate();
        }
    }

    /** The stored mandate, if this registry has recorded it. */
    public Optional<MandateView> mandate(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT mandate_id, workspace_path, industry, status FROM mandate"
                        + " WHERE mandate_id = ?")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MandateView(rs.getLong("mandate_id"),
                        rs.getString("workspace_path"), rs.getString("industry"),
                        rs.getString("status")));
            }
        }
    }

    /** Sets or clears the platform default ceiling. Null clears it. */
    public void setDefaultCeiling(Double usd) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO platform_policy (singleton, spend_ceiling_usd) VALUES (1, ?)"
                        + " ON CONFLICT(singleton) DO UPDATE SET"
                        + " spend_ceiling_usd = excluded.spend_ceiling_usd")) {
            if (usd == null) {
                ps.setNull(1, java.sql.Types.REAL);
            } else {
                ps.setDouble(1, usd);
            }
            ps.executeUpdate();
        }
    }

    /** The platform default, or null when unset. */
    public Double defaultCeiling() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT spend_ceiling_usd FROM platform_policy WHERE singleton = 1")) {
            if (!rs.next() || rs.getObject("spend_ceiling_usd") == null) {
                return null;
            }
            return rs.getDouble("spend_ceiling_usd");
        }
    }

    /**
     * Industry keys already detected for the mandate, in key order, or null
     * when none have been recorded.
     */
    public static String industries(Connection workspace, long mandateId) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (PreparedStatement ps = workspace.prepareStatement(
                "SELECT industry_key FROM mandate_industry_record"
                        + " WHERE mandate_id = ? ORDER BY industry_key")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    if (key != null && !key.isBlank()) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys.isEmpty() ? null : String.join(", ", keys);
    }

    /**
     * Records the mandate when {@code platform} is non-null, creating the registry
     * if needed. Does nothing when no registry was named.
     */
    public static void recordIfNamed(Path platform, Path workspaceDb, long mandateId,
            Connection workspace) throws SQLException {
        if (platform == null) {
            return;
        }
        try (PlatformRegistry registry = open(platform)) {
            registry.recordMandate(mandateId, workspaceDb, industries(workspace, mandateId));
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private static void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS mandate ("
                    + " mandate_id INTEGER PRIMARY KEY,"
                    + " workspace_path TEXT NOT NULL,"
                    + " industry TEXT,"
                    + " status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open'))"
                    + ")");
            statement.execute("CREATE TABLE IF NOT EXISTS platform_policy ("
                    + " singleton INTEGER PRIMARY KEY CHECK (singleton = 1),"
                    + " spend_ceiling_usd REAL"
                    + ")");
        }
    }
}
