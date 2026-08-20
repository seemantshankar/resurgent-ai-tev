package com.resurgent.tev.parser.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Thin hand-written repository for the workspace schema (ADR 0002): plain JDBC,
 * prepared statements exclusively, no business logic.
 */
public final class WorkspaceRepository {

    private final Connection connection;

    public WorkspaceRepository(Connection connection) {
        this.connection = connection;
    }

    public long insertSourceFile(long mandateId, String fileName, String fileHash,
            String fileType, String ingestedAt, String parserVersion) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_file (mandate_id, file_name, file_hash, file_type,"
                        + " ingested_at, parser_version) VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, mandateId);
            ps.setString(2, fileName);
            ps.setString(3, fileHash);
            ps.setString(4, fileType);
            ps.setString(5, ingestedAt);
            ps.setString(6, parserVersion);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long insertParseRun(long sourceFileId, long mandateId, String parserVersion,
            String configHash, String startedAt, String finishedAt, String status, String metrics)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO parse_run (source_file_id, mandate_id, parser_version, config_hash,"
                        + " started_at, finished_at, status, metrics)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sourceFileId);
            ps.setLong(2, mandateId);
            ps.setString(3, parserVersion);
            ps.setString(4, configHash);
            ps.setString(5, startedAt);
            ps.setString(6, finishedAt);
            ps.setString(7, status);
            ps.setString(8, metrics);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public long insertWorksheet(long parseRunId, String sheetName, int sheetIndex) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO worksheet (parse_run_id, sheet_name, sheet_index) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parseRunId);
            ps.setString(2, sheetName);
            ps.setInt(3, sheetIndex);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    public void insertCell(long worksheetId, String coord, int rowNum, int colNum,
            String rawValue, String rawType, String textValue) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cell (worksheet_id, coord, row_num, col_num,"
                        + " raw_value, raw_type, value_type, text_value, display_value)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, worksheetId);
            ps.setString(2, coord);
            ps.setInt(3, rowNum);
            ps.setInt(4, colNum);
            ps.setString(5, rawValue);
            ps.setString(6, rawType);
            ps.setString(7, rawType);
            ps.setString(8, textValue);
            ps.setString(9, textValue);
            ps.executeUpdate();
        }
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }
}
