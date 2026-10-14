package com.resurgent.tev.doc.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Command-level exit-code contract (spec S2.8): {@code 0} success — including partial,
 * which prints warnings — {@code 3} invalid arguments/pack, {@code 1} runtime failure.
 */
class DocExtractCommandTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long versionId;
    private long versionWithoutSourceFileId;
    private final long mandateId = 1L;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(mandateId, "quote.pdf", "hash-quote",
                    "pdf", "2026-01-01T00:00:00Z", "0.1.0", null);
            long documentId = docs.insertDocument(mandateId, "Q", "quotation", null);
            versionId = docs.insertDocumentVersion(documentId, "v1", "quote.pdf", "hash-quote",
                    ".pdf", "quotation", 1, false, sourceFileId, null);
            versionWithoutSourceFileId = docs.insertDocumentVersion(documentId, "v2",
                    "broken.pdf", "hash-broken", ".pdf", "quotation", 1, false, null, null);
        }
    }

    private record Run(int exitCode, String out, String err) {}

    /** Executes the real command through picocli, capturing stdout/stderr. */
    private Run runExtract(long version, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--mandate", Long.toString(mandateId),
                "--version", Long.toString(version),
                "--db", dbPath.toString()));
        args.addAll(List.of(extraArgs));
        CommandLine cmd = new CommandLine(new DocExtractCommand());
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        Integer exit = cmd.execute(args.toArray(String[]::new));
        return new Run(exit == null ? -1 : exit, out.toString(), err.toString());
    }

    @Test
    void partialExtractExitsZeroWithWarning() {
        // fixture extractor with no fixtures loaded returns zero fields -> partial
        Run run = runExtract(versionId, "--pack", "quotation.vendor",
                "--extractor", "fixture");
        assertThat(run.exitCode()).isZero();
        assertThat(run.err()).contains("warning").contains("partial");
        assertThat(run.out()).contains("parse_run:");
    }

    @Test
    void failedExtractExitsOne() {
        Run run = runExtract(versionWithoutSourceFileId, "--pack", "quotation.vendor",
                "--extractor", "fixture");
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.err()).contains("extract failed");
    }

    @Test
    void unknownPackExitsThree() {
        Run run = runExtract(versionId, "--pack", "no.such.pack");
        assertThat(run.exitCode()).isEqualTo(3);
        assertThat(run.err()).contains("unknown pack");
    }

    @Test
    void exitCodeMapsOutcomeStatuses() {
        assertThat(DocExtractCommand.exitCode("success")).isZero();
        assertThat(DocExtractCommand.exitCode("partial")).isZero();
        assertThat(DocExtractCommand.exitCode("failed")).isEqualTo(1);
    }
}
