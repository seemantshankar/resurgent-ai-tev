package com.resurgent.tev.doc.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.classify.DocClassifyRunner;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
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

    @Test
    void fanOutExtractPrintsOneBlockPerDocument() throws Exception {
        long fileVersionId;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            try (var ps = db.connection().prepareStatement(
                    "INSERT OR REPLACE INTO mandate_policy (mandate_id, data_residency)"
                            + " VALUES (?, 'local_only')")) {
                ps.setLong(1, mandateId);
                ps.executeUpdate();
            }
            DocumentRepository docs = new DocumentRepository(db.connection());
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(mandateId, "volume-2.pdf", "hash-vol2",
                    "pdf", "2026-01-01T00:00:00Z", "0.1.0", null);
            long fileDocId = docs.insertDocument(mandateId, "Volume 2", "other", null);
            fileVersionId = docs.insertDocumentVersion(fileDocId, "v1", "volume-2.pdf",
                    "hash-vol2", ".pdf", "other", 2, false, sourceFileId, null);
            docs.setCurrentVersion(fileDocId, fileVersionId);
            insertPageWithText(docs, fileVersionId, 1,
                    "Project cost: Rs. 120 crore",
                    "Project capacity: 25 MW",
                    "Technology: monocrystalline modules",
                    "Plant and machinery: modules and inverters");
            insertPageWithText(docs, fileVersionId, 2,
                    "The seller is Helios Power Private Limited.",
                    "The buyer is the state distribution licensee.",
                    "The tariff is Rs. 3.10 per kWh.",
                    "The term of the agreement is 25 years.");
        }
        BlobStore blobs = BlobStore.besideDatabase(dbPath);
        DocClassifyRunner.run(mandateId, fileVersionId, dbPath, blobs, null, "general", true,
                true);

        Run run = runExtract(fileVersionId, "--extractor", "doc-llm-extract", "--no-parse");
        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("document_id:");
        assertThat(run.out().split("document_id:").length).isGreaterThanOrEqualTo(3);

        List<FactRow> facts;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            facts = new FactRepository(db.connection()).query(FactQuery.forMandate(mandateId));
        }
        assertThat(facts.stream().map(FactRow::fieldKey)).contains("project_cost");
    }

    private static void insertPageWithText(DocumentRepository docs, long versionId, int pageIndex,
            String... lines) throws Exception {
        long pageId = docs.insertPage(versionId, pageIndex, null, null, null);
        int blockIndex = 0;
        for (String text : lines) {
            docs.insertBlock(pageId, blockIndex, "paragraph", null, null, null, null,
                    text, text, blockIndex, "test");
            blockIndex++;
        }
    }
}
