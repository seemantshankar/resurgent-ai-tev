package com.resurgent.tev.parser.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

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
            assertThat(count(db.connection(), "schema_migration")).isEqualTo(3);
        }
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            assertThat(count(db.connection(), "schema_migration")).isEqualTo(3);
        }
    }

    @Test
    void jsonbParsesAndSerializes() throws Exception {
        Map<String, Object> value = Map.of("sheets", List.of("Sheet1", "Sheet2"), "count", 2);
        String json = Jsonb.toJson(value);
        assertThat(Jsonb.fromJson(json, java.util.HashMap.class)).containsKey("sheets");
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

            long linkId = repo.insertExternalLink(workbookId, "external", "/path/to/file.xlsx",
                    "active", Timestamps.now());
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
            assertThat(Jsonb.fromJson(repo.selectWorkbookProperties(workbookId), Map.class))
                    .containsEntry("application", "Excel");
            assertThat(repo.selectWorkbookIsProtected(workbookId)).isTrue();
            assertThat(Jsonb.fromJson(repo.selectAuditLogPayload(auditLogId), Map.class))
                    .containsEntry("stage", "init");
            assertThat(Jsonb.fromJson(repo.selectReviewQueueDetail(reviewId), Map.class))
                    .containsEntry("cell", "A1");
            assertThat(Jsonb.fromJson(repo.selectIngestRejectionDetail(rejectionId), Map.class))
                    .containsEntry("detected", "UTF-16");
            assertThat(Jsonb.fromJson(repo.selectParseRunMetrics(parseRunId), Map.class))
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
}
