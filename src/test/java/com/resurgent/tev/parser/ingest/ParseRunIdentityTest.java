package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ParseRunIdentity}: idempotent source-file and parse-run
 * resolution anchored to (mandate_id, file_hash) and
 * (source_file_id, parser_version, config_hash).
 */
class ParseRunIdentityTest {

    @TempDir
    Path tempDir;

    private WorkspaceRepository openRepo(String dbName) throws Exception {
        WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve(dbName));
        db.connection().setAutoCommit(false);
        return new WorkspaceRepository(db.connection());
    }

    private static Long findRun(WorkspaceRepository repo, long mandateId, String fileHash,
            String parserVersion, String configHash) throws SQLException {
        return ParseRunIdentity.findExistingParseRunId(mandateId, fileHash,
                parserVersion, configHash, repo);
    }

    private static long ensureSource(WorkspaceRepository repo, long mandateId, String fileHash)
            throws SQLException {
        return ParseRunIdentity.ensureSourceFile(mandateId, "test.xlsx", fileHash,
                FileType.FM_XLSX.value(), "0.1.0", "{\"sheets\":1}", Timestamps.now(), repo);
    }

    @Test
    void findExistingParseRunId_returnsNullWhenSourceFileMissing() throws Exception {
        WorkspaceRepository repo = openRepo("missing.db");

        Long runId = findRun(repo, 1L, "abc123", "0.1.0", "cfg1");

        assertThat(runId).isNull();
    }

    @Test
    void findExistingParseRunId_returnsNullWhenParseRunMissing() throws Exception {
        WorkspaceRepository repo = openRepo("no-run.db");
        long sourceFileId = repo.insertSourceFile(1L, "test.xlsx", "abc123",
                FileType.FM_XLSX.value(), Timestamps.now(), "0.1.0", "{}");

        Long runId = findRun(repo, 1L, "abc123", "0.1.0", "cfg1");

        assertThat(runId).isNull();
        assertThat(ensureSource(repo, 1L, "abc123")).isEqualTo(sourceFileId);
    }

    @Test
    void findExistingParseRunId_returnsExistingRun() throws Exception {
        WorkspaceRepository repo = openRepo("existing.db");
        long sourceFileId = repo.insertSourceFile(1L, "test.xlsx", "abc123",
                FileType.FM_XLSX.value(), Timestamps.now(), "0.1.0", "{}");
        long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg1",
                Timestamps.now(), Timestamps.now(), "success", "{}");

        Long runId = findRun(repo, 1L, "abc123", "0.1.0", "cfg1");

        assertThat(runId).isEqualTo(parseRunId);
    }

    @Test
    void bumpedParserVersion_returnsNoExistingRun() throws Exception {
        WorkspaceRepository repo = openRepo("version.db");
        long sourceFileId = repo.insertSourceFile(1L, "test.xlsx", "def456",
                FileType.FM_XLSX.value(), Timestamps.now(), "0.1.0", "{}");
        repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg1",
                Timestamps.now(), Timestamps.now(), "success", "{}");

        Long runId = findRun(repo, 1L, "def456", "0.2.0", "cfg1");

        assertThat(runId).isNull();
    }

    @Test
    void changedConfigHash_returnsNoExistingRun() throws Exception {
        WorkspaceRepository repo = openRepo("config.db");
        long sourceFileId = repo.insertSourceFile(1L, "test.xlsx", "ghi789",
                FileType.FM_XLSX.value(), Timestamps.now(), "0.1.0", "{}");
        repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg-a",
                Timestamps.now(), Timestamps.now(), "success", "{}");

        Long runId = findRun(repo, 1L, "ghi789", "0.1.0", "cfg-b");

        assertThat(runId).isNull();
    }

    @Test
    void sameFileUnderTwoMandates_hasIndependentSourceFiles() throws Exception {
        WorkspaceRepository repo = openRepo("mandate.db");
        long sourceFileId1 = repo.insertSourceFile(1L, "test.xlsx", "jkl012",
                FileType.FM_XLSX.value(), Timestamps.now(), "0.1.0", "{}");
        long parseRunId1 = repo.insertParseRun(sourceFileId1, 1L, "0.1.0", "cfg1",
                Timestamps.now(), Timestamps.now(), "success", "{}");

        Long runIdForMandate2 = findRun(repo, 2L, "jkl012", "0.1.0", "cfg1");
        long sourceFileId2 = ensureSource(repo, 2L, "jkl012");
        long parseRunId2 = repo.insertParseRun(sourceFileId2, 2L, "0.1.0", "cfg1",
                Timestamps.now(), Timestamps.now(), "success", "{}");

        assertThat(runIdForMandate2).isNull();
        assertThat(sourceFileId2).isNotEqualTo(sourceFileId1);
        assertThat(parseRunId2).isNotEqualTo(parseRunId1);
        assertThat(repo.countSourceFiles()).isEqualTo(2);
        assertThat(repo.countParseRuns()).isEqualTo(2);
    }

    @Test
    void ensureSourceFile_reusesExistingRow() throws Exception {
        WorkspaceRepository repo = openRepo("reuse.db");
        long existingSourceFileId = repo.insertSourceFile(1L, "test.xlsx", "pqr678",
                FileType.FM_XLSX.value(), Timestamps.now(), "0.1.0", "{}");

        long sourceFileId = ensureSource(repo, 1L, "pqr678");

        assertThat(sourceFileId).isEqualTo(existingSourceFileId);
        assertThat(repo.countSourceFiles()).isEqualTo(1);
    }

    @Test
    void ensureSourceFile_createsNewRowWhenMissing() throws Exception {
        WorkspaceRepository repo = openRepo("create.db");

        long sourceFileId = ensureSource(repo, 1L, "mno345");

        assertThat(sourceFileId).isPositive();
        assertThat(repo.countSourceFiles()).isEqualTo(1);
    }
}
