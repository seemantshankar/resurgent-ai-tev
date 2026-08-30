package com.resurgent.tev.parser.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

import com.resurgent.tev.parser.ingest.NormalizedCell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Persistence-seam test: exercises the SQLite workspace schema, migrations,
 * repositories, and Jackson converters against real temporary DB files.
 */
class PersistenceSeamTest {

    @TempDir
    Path tempDir;

    private WorkspaceDatabase openDb(String name) throws Exception {
        return WorkspaceDatabase.open(tempDir.resolve(name));
    }

    private static List<String> tableNames(java.sql.Connection c) throws Exception {
        try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[] { "TABLE" })) {
            List<String> names = new java.util.ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
            return names;
        }
    }

    private static long count(java.sql.Connection c, String table) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void applyV9Schema(Path dbPath) throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            for (int version = 1; version <= 9; version++) {
                String resource = "db/migration/V" + version + "__" + switch (version) {
                    case 1 -> "initial_schema";
                    case 2 -> "source_file_raw_metadata";
                    case 3 -> "sprint1_schema";
                    case 4 -> "xlsx_cell_contract";
                    case 5 -> "cell_header_labels";
                    case 6 -> "structural_and_external_refs";
                    case 7 -> "audit_log_nullable_parse_run";
                    case 8 -> "sprint2_schema";
                    default -> "error_barriers";
                } + ".sql";
                String sql = new String(PersistenceSeamTest.class.getClassLoader()
                        .getResourceAsStream(resource).readAllBytes(), StandardCharsets.UTF_8);
                try (java.sql.Statement statement = connection.createStatement()) {
                    for (String part : sql.split(";\\s*\\R")) {
                        String trimmed = part.replaceAll("(?m)^--.*$", "").trim();
                        if (!trimmed.isEmpty()) {
                            statement.execute(trimmed);
                        }
                    }
                }
            }
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE schema_migration (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
                for (int version = 1; version <= 9; version++) {
                    statement.execute("INSERT INTO schema_migration VALUES (" + version + ", 'now')");
                }
            }
        }
    }

    @Test
    void sprint1TablesAreCreated() throws Exception {
        try (WorkspaceDatabase db = openDb("sprint1.db")) {
            List<String> tables = tableNames(db.connection());
            assertThat(tables).contains(
                    "source_file",
                    "parse_run",
                    "workbook",
                    "worksheet",
                    "external_link",
                    "cell",
                    "provenance",
                    "audit_log",
                    "review_queue",
                    "ingest_rejection");
        }
    }

    @Test
    void migrationsAreIdempotent() throws Exception {
        Path dbPath = tempDir.resolve("idempotent.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            assertThat(count(db.connection(), "schema_migration")).isEqualTo(15);
        }
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            assertThat(count(db.connection(), "schema_migration")).isEqualTo(15);
        }
    }

    @Test
    void jsonbParsesAndSerializes() throws Exception {
        Map<String, Object> value = Map.of("sheets", List.of("Sheet1", "Sheet2"), "count", 2);
        String json = Jsonb.toJson(value);
        assertThat(Jsonb.fromJson(json, new TypeReference<Map<String, Object>>() {}))
                .containsKey("sheets");
        assertThatThrownBy(() -> Jsonb.fromJson("{not json", Object.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void jsonbRoundTripsIntArray() throws Exception {
        int[] values = {1, 2, 3};
        String json = Jsonb.toJsonArray(values);
        assertThat(Jsonb.fromJsonArray(json)).containsExactly(1, 2, 3);
    }

    @Test
    void repositoryRoundTripsJsonAndBooleanColumns() throws Exception {
        try (WorkspaceDatabase db = openDb("roundtrip.db")) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());

            long sourceFileId = repo.insertSourceFile(42L, "fm.xlsx", "abc123", "fm_xlsx",
                    Timestamps.now(), "0.1.0", Jsonb.toJson(Map.of("encoding", "UTF-8")));
            long parseRunId = repo.insertParseRun(sourceFileId, 42L, "0.1.0", "cfg",
                    Timestamps.now(), Timestamps.now(), "success",
                    Jsonb.toJson(Map.of("cells", 9)));

            List<String> sheetNames = List.of("Sheet1", "Sheet2");
            Map<String, Object> props = Map.of("application", "Excel", "version", 16);
            long workbookId = repo.insertWorkbook(sourceFileId, "Excel", "16.0", 2,
                    Jsonb.toJson(sheetNames), "[]", Jsonb.toJson(props), true,
                    Timestamps.now(), Timestamps.now());

            long linkId = repo.insertExternalLink(workbookId, "external", 1,
                    "/path/to/file.xlsx", "active", Timestamps.now());
            long provenanceId = repo.insertProvenance("cell", 1L, sourceFileId, parseRunId,
                    "Sheet1!A1", "100", 0.95, false, "primary value");
            long auditLogId = repo.insertAuditLog(parseRunId, "parse_started", Timestamps.now(),
                    Jsonb.toJson(Map.of("stage", "init")), "info");
            long reviewId = repo.insertReviewQueue(parseRunId, "formula_error", "REF! in Sheet1",
                    Jsonb.toJson(Map.of("cell", "A1")), "Pending", false,
                    Timestamps.now(), null);
            long rejectionId = repo.insertIngestRejection(null, 42L, "bad.csv", "hash",
                    "unsupported_encoding", Jsonb.toJson(Map.of("detected", "UTF-16")),
                    Timestamps.now());

            assertThat(Jsonb.fromJson(repo.selectWorkbookSheetNames(workbookId),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}))
                    .isEqualTo(sheetNames);
            assertThat(Jsonb.fromJson(repo.selectWorkbookProperties(workbookId),
                    new TypeReference<Map<String, Object>>() {}))
                    .containsEntry("application", "Excel");
            assertThat(repo.selectWorkbookIsProtected(workbookId)).isTrue();
            assertThat(Jsonb.fromJson(repo.selectAuditLogPayload(auditLogId),
                    new TypeReference<Map<String, Object>>() {}))
                    .containsEntry("stage", "init");
            assertThat(Jsonb.fromJson(repo.selectReviewQueueDetail(reviewId),
                    new TypeReference<Map<String, Object>>() {}))
                    .containsEntry("cell", "A1");
            assertThat(Jsonb.fromJson(repo.selectIngestRejectionDetail(rejectionId),
                    new TypeReference<Map<String, Object>>() {}))
                    .containsEntry("detected", "UTF-16");
            assertThat(Jsonb.fromJson(repo.selectParseRunMetrics(parseRunId),
                    new TypeReference<Map<String, Object>>() {}))
                    .containsEntry("cells", 9);

            assertThat(repo.countWorkbooks()).isEqualTo(1);
            assertThat(repo.countExternalLinks()).isEqualTo(1);
            assertThat(repo.countProvenance()).isEqualTo(1);
            assertThat(repo.countAuditLogs()).isEqualTo(1);
            assertThat(repo.countReviewQueue()).isEqualTo(1);
            assertThat(repo.countIngestRejections()).isEqualTo(1);
        }
    }

    @Test
    void auditLogAcceptsNullParseRunForPreParseRunRejections() throws Exception {
        try (WorkspaceDatabase db = openDb("audit-null-parse-run.db")) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());

            long auditLogId = repo.insertAuditLog(null, "ingest_rejected", Timestamps.now(),
                    Jsonb.toJson(Map.of("reasonCode", "file_too_large")), "warning");

            try (ResultSet rs = db.connection().createStatement().executeQuery(
                    "SELECT parse_run_id, event_type, severity FROM audit_log"
                            + " WHERE audit_log_id = " + auditLogId)) {
                assertThat(rs.next()).isTrue();
                rs.getLong("parse_run_id");
                assertThat(rs.wasNull()).isTrue();
                assertThat(rs.getString("event_type")).isEqualTo("ingest_rejected");
                assertThat(rs.getString("severity")).isEqualTo("warning");
            }
        }
    }

    @Test
    void cellValuesRoundTrip() throws Exception {
        try (WorkspaceDatabase db = openDb("values.db")) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(1L, "values.xlsx", "hash", "fm_xlsx",
                    Timestamps.now(), "0.1.0", null);
            long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg",
                    Timestamps.now(), Timestamps.now(), "success", null);
            long worksheetId = repo.insertWorksheet(parseRunId, "Sheet1", 0, "visible");

            NormalizedCell cell = new NormalizedCell(
                    "B2", 2, 2,
                    "100", "number", "number", "100", "100",
                    java.math.BigDecimal.valueOf(100), null, null,
                    null, null, null, null, false,
                    false, null,
                    false, false, null, "cell", false, false, false);
            repo.insertCell(worksheetId, cell);

            try (ResultSet rs = db.connection().createStatement().executeQuery(
                    "SELECT numeric_value, formula_text FROM cell WHERE coord = 'B2'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal("numeric_value")).isEqualByComparingTo("100");
                assertThat(rs.getString("formula_text")).isNull();
            }
        }
    }

    @Test
    void booleanColumnsAreConstrainedToZeroOrOne() throws Exception {
        try (WorkspaceDatabase db = openDb("boolean.db")) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(7L, "b.xlsx", "hash", "fm_xlsx",
                    Timestamps.now(), "0.1.0", null);
            repo.insertWorkbook(sourceFileId, null, null, 1, null, null, null, false, null, null);

            assertThatThrownBy(() -> {
                try (java.sql.PreparedStatement ps = db.connection().prepareStatement(
                        "INSERT INTO workbook (source_file_id, sheet_count, is_protected)"
                                + " VALUES (?, 1, 2)")) {
                    ps.setLong(1, sourceFileId);
                    ps.executeUpdate();
                }
            }).isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    void transactionRollbackLeavesNoPartialWrites() throws Exception {
        try (WorkspaceDatabase db = openDb("rollback.db")) {
            java.sql.Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);

            c.setAutoCommit(false);
            repo.insertSourceFile(1L, "r.xlsx", "h1", "fm_xlsx", Timestamps.now(), "0.1.0", null);
            repo.insertSourceFile(2L, "r2.xlsx", "h2", "fm_xlsx", Timestamps.now(), "0.1.0", null);
            c.rollback();
            c.setAutoCommit(true);

            assertThat(repo.countSourceFiles()).isEqualTo(0);
        }
    }

    @Test
    void forcedFailureMidGraphInsert_rollsBackEntireGraph() throws Exception {
        try (WorkspaceDatabase db = openDb("mid-graph-failure.db")) {
            java.sql.Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);

            c.setAutoCommit(false);
            try {
                long sourceFileId = repo.insertSourceFile(1L, "f.xlsx", "h1", "fm_xlsx",
                        Timestamps.now(), "0.1.0", null);
                long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg",
                        Timestamps.now(), null, "success", "{}");
                long worksheetId = repo.insertWorksheet(parseRunId, "Sheet1", 0);

                NormalizedCell goodCell = new NormalizedCell(
                        "A1", 1, 1, "1", "number", "number", "1", "1",
                        java.math.BigDecimal.ONE, null, null,
                        null, null, null, null, false,
                        false, null,
                        false, false, null, "cell", false, false, false);
                repo.insertCell(worksheetId, goodCell);

                // Forces a genuine SQLException mid-graph via a raw statement, simulating
                // an unexpected failure after some of the graph has already been written.
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cell (worksheet_id, coord, row_num, col_num, raw_type,"
                                + " value_type, bool_value) VALUES (?, 'A2', 1, 2, 'bool', 'bool', 2)")) {
                    ps.setLong(1, worksheetId);
                    ps.executeUpdate();
                    fail("expected the bool_value CHECK constraint to reject value 2");
                }
            } catch (java.sql.SQLException e) {
                c.rollback();
            } finally {
                c.setAutoCommit(true);
            }

            assertThat(repo.countSourceFiles()).isEqualTo(0);
            assertThat(repo.countParseRuns()).isEqualTo(0);
            assertThat(repo.countWorksheets()).isEqualTo(0);
            assertThat(repo.countCells()).isEqualTo(0);
        }
    }

    @Test
    void v8MigrationAppliesCleanlyAndWorkbookCalcMetadataPersists() throws Exception {
        try (WorkspaceDatabase db = openDb("v8.db")) {
            java.sql.Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);

            assertThat(count(c, "schema_migration")).isEqualTo(15);
            assertThat(tableNames(c)).doesNotContain("cell_reference", "cell_error_root");

            long sourceFileId = repo.insertSourceFile(1L, "v8.xlsx", "hash8", "fm_xlsx",
                    Timestamps.now(), "0.1.0", null);
            long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg",
                    Timestamps.now(), null, "success", "{}");
            long worksheetId = repo.insertWorksheet(parseRunId, "Sheet1", 0);
            long workbookId = repo.insertWorkbook(sourceFileId, "Excel", "16.0", 1,
                    "[\"Sheet1\"]", "[]", "{}", false, Timestamps.now(), Timestamps.now());

            NormalizedCell cell = new NormalizedCell(
                    "A1", 1, 1, "100", "number", "number", "100", "100",
                    new java.math.BigDecimal("100"), null, null,
                    null, null, null, null, false,
                    false, null,
                    false, false, null, "cell", false, false, false);
            assertThat(repo.insertCell(worksheetId, cell)).isGreaterThan(0L);

            repo.updateWorkbookCalcMetadata(workbookId, "auto", true, true, false, 0, 0);

            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT calculation_mode, full_calc_on_load FROM workbook WHERE workbook_id = " + workbookId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("calculation_mode")).isEqualTo("auto");
                assertThat(rs.getInt("full_calc_on_load")).isEqualTo(1);
            }
        }
    }

    @Test
    void v10SchemaAddsRegionColumnsWhenStoppedAtVersion10() throws Exception {
        Path dbPath = tempDir.resolve("v10-only.db");
        LegacyWorkspaceFactory.applyThroughVersion(dbPath, 10);
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            assertThat(tableNames(c)).contains("region");
            assertThat(count(c, "schema_migration")).isEqualTo(10);
            assertThat(columnNames(c, "cell")).contains("region_id", "formula_skeleton_regional",
                    "is_bold", "has_fill", "has_border", "number_format");
            assertThat(columnNames(c, "worksheet")).contains("role", "role_conf");
        }
    }

    @Test
    void v14MigrationUsesLeanCellContract() throws Exception {
        try (WorkspaceDatabase db = openDb("v14.db")) {
            java.sql.Connection c = db.connection();
            assertThat(count(c, "schema_migration")).isEqualTo(15);
            assertThat(tableNames(c)).doesNotContain(
                    "region",
                    "cost_head",
                    "cell_reference",
                    "cell_error_root");
            assertThat(columnNames(c, "cell")).contains(
                    "formula_text", "formula_state", "cached_value", "is_merged_anchor");
            assertThat(columnNames(c, "cell")).doesNotContain(
                    "formula_normalized", "parsed_quantity", "row_label", "col_label",
                    "is_bold", "has_fill", "has_border", "number_format", "tags",
                    "region_id", "formula_skeleton");
            assertThat(columnNames(c, "worksheet")).doesNotContain("role", "role_conf", "role_reasons");
            try (ResultSet rs = c.createStatement().executeQuery("PRAGMA foreign_key_check")) {
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void v10MigrationUpgradesPopulatedV9DatabaseWithoutLosingCells() throws Exception {
        Path dbPath = tempDir.resolve("populated-v9.db");
        applyV9Schema(dbPath);
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO source_file (mandate_id, file_name, file_hash, file_type, ingested_at, parser_version) VALUES (1, 'a.xlsx', 'hash', 'fm_xlsx', 'now', 'v1')");
            statement.execute("INSERT INTO parse_run (source_file_id, mandate_id, parser_version, config_hash, started_at, status) VALUES (1, 1, 'v1', 'cfg', 'now', 'success')");
            statement.execute("INSERT INTO worksheet (parse_run_id, sheet_name, sheet_index) VALUES (1, 'Sheet1', 0)");
            statement.execute("INSERT INTO cell (worksheet_id, coord, row_num, col_num, raw_type, value_type, formula_skeleton, is_error_barrier) VALUES (1, 'A1', 1, 1, 'number', 'number', '=R', 1)");
        }
        assertThatThrownBy(() -> WorkspaceDatabase.open(dbPath))
                .isInstanceOf(DestructiveResetRequiredException.class)
                .hasMessageContaining(dbPath.toAbsolutePath().normalize().toString());

        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                ResultSet rs = connection.createStatement().executeQuery(
                        "SELECT coord, formula_skeleton, is_error_barrier FROM cell")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("coord")).isEqualTo("A1");
            assertThat(rs.getString("formula_skeleton")).isEqualTo("=R");
            assertThat(rs.getInt("is_error_barrier")).isEqualTo(1);
            assertThat(count(connection, "schema_migration")).isEqualTo(10);
            assertThat(tableNames(connection)).doesNotContain("cost_head");
        }
    }

    @Test
    void v11MigrationAppliesAutomaticallyOnEmptyWorkspace() throws Exception {
        Path dbPath = tempDir.resolve("v11-only.db");
        LegacyWorkspaceFactory.applyThroughVersion(dbPath, 11);
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            assertThat(count(c, "schema_migration")).isEqualTo(11);
            assertThat(tableNames(c)).contains(
                    "cost_head",
                    "cost_head_mapping",
                    "cost_head_candidate",
                    "cost_head_contribution",
                    "cost_head_contribution_cell",
                    "duplicate_proposal",
                    "duplicate_decision",
                    "manual_contribution",
                    "cost_head_mapping_decision",
                    "cost_head_total_decision");
            assertThat(columnNames(c, "region")).contains(
                    "inferred_currency_conf", "inferred_unit_conf", "semantic_region_type");
            assertThat(columnNames(c, "cell")).contains("is_support", "support_reason");
            assertThat(columnNames(c, "review_queue")).contains(
                    "subject_kind", "subject_key", "confidence", "carried_from_decision_id");
            try (ResultSet rs = c.createStatement().executeQuery("PRAGMA foreign_key_check")) {
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void v11MigrationRefusesPopulatedV10WorkspaceWithoutOptIn() throws Exception {
        Path dbPath = tempDir.resolve("populated-v10.db");
        LegacyWorkspaceFactory.writePopulatedV10(dbPath);

        assertThatThrownBy(() -> WorkspaceDatabase.open(dbPath))
                .isInstanceOf(DestructiveResetRequiredException.class)
                .hasMessageContaining(dbPath.toAbsolutePath().normalize().toString())
                .hasMessageContaining("parser-owned operational data");

        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            assertThat(count(connection, "cell")).isEqualTo(1);
            assertThat(count(connection, "source_file")).isEqualTo(1);
            assertThat(count(connection, "schema_migration")).isEqualTo(10);
            assertThat(tableNames(connection)).doesNotContain("cost_head");
        }
    }

    @Test
    void v11MigrationResetsPopulatedWorkspaceWhenOptedIn() throws Exception {
        Path dbPath = tempDir.resolve("reset-v10.db");
        LegacyWorkspaceFactory.writePopulatedV10(dbPath);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(
                dbPath, WorkspaceDatabase.OpenOptions.allowDestructiveReset())) {
            java.sql.Connection c = db.connection();
            assertThat(count(c, "schema_migration")).isEqualTo(15);
            assertThat(count(c, "cell")).isZero();
            assertThat(count(c, "source_file")).isZero();
            assertThat(tableNames(c)).doesNotContain("cost_head", "region");
            try (ResultSet rs = c.createStatement().executeQuery("PRAGMA foreign_key_check")) {
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void v11MigrationRollsBackWhenCommitIsInjectedToFail() throws Exception {
        Path dbPath = tempDir.resolve("v11-fault.db");
        LegacyWorkspaceFactory.writePopulatedV10(dbPath);
        assertThatThrownBy(() -> WorkspaceDatabase.open(
                dbPath, WorkspaceDatabase.OpenOptions.injectV11Failure()))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("injected V11 failure");

        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            assertThat(count(connection, "cell")).isEqualTo(1);
            assertThat(count(connection, "schema_migration")).isEqualTo(10);
            assertThat(tableNames(connection)).doesNotContain("cost_head");
        }
    }

    private static java.util.Set<String> columnNames(java.sql.Connection c, String table) throws Exception {
        java.util.Set<String> columns = new java.util.HashSet<>();
        try (ResultSet rs = c.createStatement().executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }
}
