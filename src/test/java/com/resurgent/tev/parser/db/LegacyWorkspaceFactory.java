package com.resurgent.tev.parser.db;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Builds a populated Sprint 3a (V10) workspace without running later migrations.
 * Used by persistence and CLI tests that must observe the V11 reset guard.
 */
public final class LegacyWorkspaceFactory {

    private LegacyWorkspaceFactory() {}

    public static void writePopulatedV10(Path dbPath) throws Exception {
        applyThroughVersion(dbPath, 10);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO source_file (mandate_id, file_name, file_hash, file_type,
                        ingested_at, parser_version)
                    VALUES (1, 'legacy.xlsx', 'v10-hash', 'fm_xlsx', 'now', 'v1')
                    """);
            statement.execute("""
                    INSERT INTO parse_run (source_file_id, mandate_id, parser_version, config_hash,
                        started_at, status)
                    VALUES (1, 1, 'v1', 'cfg', 'now', 'success')
                    """);
            statement.execute("""
                    INSERT INTO worksheet (parse_run_id, sheet_name, sheet_index)
                    VALUES (1, 'Sheet1', 0)
                    """);
            statement.execute("""
                    INSERT INTO cell (worksheet_id, coord, row_num, col_num, raw_type, value_type)
                    VALUES (1, 'Z9', 9, 26, 'number', 'number')
                    """);
        }
    }

    static void applyThroughVersion(Path dbPath, int throughVersion) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement statement = connection.createStatement()) {
            for (int version = 1; version <= throughVersion; version++) {
                String resource = "db/migration/V" + version + "__" + fileStem(version) + ".sql";
                String sql = new String(LegacyWorkspaceFactory.class.getClassLoader()
                        .getResourceAsStream(resource).readAllBytes(), StandardCharsets.UTF_8);
                for (String part : sql.split(";\\s*\\R")) {
                    String trimmed = part.replaceAll("(?m)^--.*$", "").trim();
                    if (!trimmed.isEmpty()) {
                        statement.execute(trimmed);
                    }
                }
            }
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migration (
                        version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)
                    """);
            for (int version = 1; version <= throughVersion; version++) {
                statement.execute("INSERT INTO schema_migration VALUES (" + version + ", 'now')");
            }
        }
    }

    private static String fileStem(int version) {
        return switch (version) {
            case 1 -> "initial_schema";
            case 2 -> "source_file_raw_metadata";
            case 3 -> "sprint1_schema";
            case 4 -> "xlsx_cell_contract";
            case 5 -> "cell_header_labels";
            case 6 -> "structural_and_external_refs";
            case 7 -> "audit_log_nullable_parse_run";
            case 8 -> "sprint2_schema";
            case 9 -> "error_barriers";
            case 10 -> "sprint3a_region_schema";
            case 11 -> "sprint3b_schema";
            case 12 -> "worksheet_role_reasons";
            default -> throw new IllegalArgumentException("unsupported version " + version);
        };
    }
}
