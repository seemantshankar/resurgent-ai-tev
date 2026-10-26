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

    /**
     * Same as {@link #recordIfNamed(Path, Path, long, Connection)}, opening the
     * workspace to read an industry this command has already detected.
     */
    public static void recordIfNamed(Path platform, Path workspaceDb, long mandateId)
            throws SQLException {
        if (platform == null) {
            return;
        }
        try (com.resurgent.tev.parser.db.WorkspaceDatabase workspace =
                com.resurgent.tev.parser.db.WorkspaceDatabase.open(workspaceDb)) {
            recordIfNamed(platform, workspaceDb, mandateId, workspace.connection());
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
            statement.execute("CREATE TABLE IF NOT EXISTS published_pack ("
                    + " industry TEXT NOT NULL,"
                    + " code TEXT NOT NULL,"
                    + " version TEXT NOT NULL,"
                    + " file_hash TEXT NOT NULL,"
                    + " status TEXT NOT NULL DEFAULT 'published'"
                    + " CHECK (status IN ('published', 'withdrawn')),"
                    + " contributing_mandates TEXT NOT NULL,"
                    + " yaml BLOB NOT NULL,"
                    + " PRIMARY KEY (industry, code, version)"
                    + ")");
            statement.execute("CREATE TABLE IF NOT EXISTS published_checklist ("
                    + " code TEXT NOT NULL,"
                    + " version TEXT NOT NULL,"
                    + " file_hash TEXT NOT NULL,"
                    + " country TEXT,"
                    + " industry TEXT,"
                    + " status TEXT NOT NULL DEFAULT 'published'"
                    + " CHECK (status IN ('published', 'withdrawn')),"
                    + " contributing_mandates TEXT NOT NULL,"
                    + " yaml BLOB NOT NULL,"
                    + " PRIMARY KEY (code, version)"
                    + ")");
        }
    }

    /**
     * Records one published pack version. An existing version row is left in place.
     * Status of a new row is {@code published}.
     */
    public void publishPack(String code, String version, String fileHash, String industry,
            List<String> contributingMandates, byte[] yaml) throws SQLException {
        String mandates = contributingMandates == null ? "" : String.join(", ", contributingMandates);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO published_pack (industry, code, version, file_hash, status,"
                        + " contributing_mandates, yaml)"
                        + " VALUES (?, ?, ?, ?, 'published', ?, ?)"
                        + " ON CONFLICT(industry, code, version) DO NOTHING")) {
            ps.setString(1, industry);
            ps.setString(2, code);
            ps.setString(3, version);
            ps.setString(4, fileHash);
            ps.setString(5, mandates);
            ps.setBytes(6, yaml);
            ps.executeUpdate();
        }
    }

    /**
     * Records one published industry checklist. The country spine is not a
     * published checklist and is ignored. An existing version row is left in place.
     */
    public void publishChecklist(String code, String version, String fileHash, String country,
            String industry, List<String> contributingMandates, byte[] yaml) throws SQLException {
        if (code == null || com.resurgent.tev.doc.checklist.ChecklistDefinition.isSpineCode(code)) {
            return;
        }
        String mandates = contributingMandates == null ? "" : String.join(", ", contributingMandates);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO published_checklist (code, version, file_hash, country, industry,"
                        + " status, contributing_mandates, yaml)"
                        + " VALUES (?, ?, ?, ?, ?, 'published', ?, ?)"
                        + " ON CONFLICT(code, version) DO NOTHING")) {
            ps.setString(1, code);
            ps.setString(2, version);
            ps.setString(3, fileHash);
            ps.setString(4, country);
            ps.setString(5, industry);
            ps.setString(6, mandates);
            ps.setBytes(7, yaml);
            ps.executeUpdate();
        }
    }

    /** One published pack or checklist version the registry can still copy. */
    public record PublishedVersion(String code, String version, String fileHash, String industry,
            String country, byte[] yaml) {}

    /**
     * Marks one published pack version withdrawn. The row stays, and its stored
     * bytes are not rewritten.
     */
    public void withdrawPack(String industry, String code, String version) throws SQLException {
        if (industry == null || industry.isBlank() || code == null || code.isBlank()
                || version == null || version.isBlank()) {
            throw new IllegalArgumentException("pack must name an industry and code@version");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE published_pack SET status = 'withdrawn'"
                        + " WHERE industry = ? AND code = ? AND version = ?"
                        + " AND status = 'published'")) {
            ps.setString(1, industry);
            ps.setString(2, code);
            ps.setString(3, version);
            if (ps.executeUpdate() != 1) {
                throw new IllegalArgumentException("no published pack " + code + "@" + version
                        + " industry " + industry);
            }
        }
    }

    /**
     * Marks one published checklist version withdrawn. The row stays, and its
     * stored bytes are not rewritten. The country spine is not a published checklist.
     */
    public void withdrawChecklist(String code, String version) throws SQLException {
        if (code == null || code.isBlank()
                || com.resurgent.tev.doc.checklist.ChecklistDefinition.isSpineCode(code)
                || version == null || version.isBlank()) {
            throw new IllegalArgumentException("checklist must name a published code@version");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE published_checklist SET status = 'withdrawn'"
                        + " WHERE code = ? AND version = ? AND status = 'published'")) {
            ps.setString(1, code);
            ps.setString(2, version);
            if (ps.executeUpdate() != 1) {
                throw new IllegalArgumentException(
                        "no published checklist " + code + "@" + version);
            }
        }
    }

    /** Pack versions whose status is published, highest version not implied. */
    public List<PublishedVersion> publishedPacks() throws SQLException {
        List<PublishedVersion> versions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT code, version, file_hash, industry, yaml FROM published_pack"
                                + " WHERE status = 'published'")) {
            while (rs.next()) {
                versions.add(new PublishedVersion(rs.getString("code"), rs.getString("version"),
                        rs.getString("file_hash"), rs.getString("industry"), null,
                        rs.getBytes("yaml")));
            }
        }
        return versions;
    }

    /** Industry checklists whose status is published. The spine is not one of these. */
    public List<PublishedVersion> publishedChecklists() throws SQLException {
        List<PublishedVersion> versions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT code, version, file_hash, country, industry, yaml"
                                + " FROM published_checklist WHERE status = 'published'")) {
            while (rs.next()) {
                versions.add(new PublishedVersion(rs.getString("code"), rs.getString("version"),
                        rs.getString("file_hash"), rs.getString("industry"),
                        rs.getString("country"), rs.getBytes("yaml")));
            }
        }
        return versions;
    }
}
