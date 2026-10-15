package com.resurgent.tev.doc.ingest;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * M1 ingest: immutable {@code document_version} + content-addressed blob + page rows.
 * No classification and no meaning at ingest (Architecture principle 9).
 */
public final class DocumentIngestService {

    public static final String PARSER_VERSION = "0.1.0-doc-m1";

    static {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("apple.awt.UIElement", "true");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record IngestRequest(
            Path file,
            long mandateId,
            String logicalName,
            String versionLabel,
            String fileRole,
            String docFamily) {}

    public record IngestResult(
            long documentId,
            long documentVersionId,
            long sourceFileId,
            long parseRunId,
            String fileHash,
            String fileName,
            String fileExt,
            String fileRole,
            int pageCount,
            boolean ocrRequired,
            boolean reused,
            Path blobPath,
            Path dbPath) {}

    /**
     * Ingests a PDF or supported image into a workspace, creating its logical document
     * when needed. Matching bytes reuse a version within the same logical document;
     * a new version becomes current, receives page placeholders, and is linked to
     * any other versions with the same hash.
     *
     * <p>A null or blank label defaults to {@code v1}; null or blank role and family
     * use defaults
     * for the extension and role, respectively. Images require OCR; PDFs use a
     * sample of at most three pages to estimate that need. Page images are not
     * created here. Reuse retains stored metadata and does not restore missing blobs.
     *
     * <p>Opens and migrates the workspace. Ingest database writes are committed
     * together and rollback is attempted on failure; copied blobs can remain.
     *
     * @return stored identity, page/OCR metadata, paths, and whether a version was reused
     * @throws IllegalArgumentException if the file extension is unsupported
     * @throws IOException if the input is not a regular file, or reading the input,
     *         probing the PDF, or storing its original bytes fails
     * @throws SQLException if workspace or ingest database operations fail
     * @throws RuntimeException if creating the logical document fails with a database error
     * @throws NoSuchAlgorithmException if SHA-256 is unavailable
     */
    public IngestResult ingest(IngestRequest request, Path dbPath, BlobStore blobStore)
            throws Exception {
        Path file = request.file().toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IOException("not a file: " + file);
        }
        DocumentFileKind kind = DocumentFileKind.fromPath(file);
        if (kind == DocumentFileKind.UNKNOWN) {
            throw new IllegalArgumentException(
                    "unsupported document extension: " + file.getFileName()
                            + " (expected pdf or image)");
        }
        String fileExt = DocumentFileKind.fileExt(file);
        if (fileExt.isEmpty()) {
            fileExt = kind == DocumentFileKind.PDF ? ".pdf" : ".bin";
        }
        String fileRole = request.fileRole() == null || request.fileRole().isBlank()
                ? kind.defaultFileRole()
                : DocumentFileKind.normalizeFileRole(request.fileRole());
        String docFamily = request.docFamily() == null || request.docFamily().isBlank()
                ? DocumentFileKind.defaultDocFamily(fileRole)
                : request.docFamily();
        String versionLabel = request.versionLabel() == null || request.versionLabel().isBlank()
                ? "v1"
                : request.versionLabel();
        String fileHash = sha256Hex(file);

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            db.connection().setAutoCommit(false);
            try {
                Optional<Long> existingDoc = docs.findDocumentId(request.mandateId(),
                        request.logicalName());
                long documentId = existingDoc.orElseGet(() -> {
                    try {
                        return docs.insertDocument(request.mandateId(), request.logicalName(),
                                docFamily, null);
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });

                Optional<Long> existingVersion = docs.findVersionIdByHash(documentId, fileHash);
                if (existingVersion.isPresent()) {
                    DocumentRepository.DocumentVersionRow row =
                            docs.findVersion(existingVersion.get()).orElseThrow();
                    Path blob = blobStore.originalPath(fileHash, row.fileExt());
                    long parseRunId = ensureParseRun(repo, row, request.mandateId(), fileHash);
                    db.connection().commit();
                    return new IngestResult(documentId, row.documentVersionId(), row.sourceFileId(),
                            parseRunId, fileHash, row.fileName(), row.fileExt(), row.fileRole(),
                            row.pageCount() == null ? 0 : row.pageCount(), row.ocrRequired(),
                            true, blob, dbPath.toAbsolutePath().normalize());
                }

                String now = Timestamps.now();
                String rawMetadata = writeMetadata(kind, file);
                long sourceFileId = ensureSourceFile(repo, request.mandateId(), file, fileHash,
                        kind, rawMetadata, now);
                Path blobPath = blobStore.storeOriginal(fileHash, fileExt, file);

                PageProbe probe = probePages(file, kind);
                long documentVersionId = docs.insertDocumentVersion(documentId, versionLabel,
                        file.getFileName().toString(), fileHash, fileExt, fileRole, probe.pageCount(),
                        probe.ocrRequired(), sourceFileId, rawMetadata);
                linkDuplicates(docs, documentVersionId, fileHash);

                // Page placeholders — layout fills geometry and text.
                for (int i = 1; i <= probe.pageCount(); i++) {
                    docs.insertPage(documentVersionId, i, null, null,
                            blobStore.pageRelativePath(fileHash, i));
                }
                docs.setCurrentVersion(documentId, documentVersionId);
                docs.touchDocument(documentId);

                long parseRunId = ensureParseRun(repo, sourceFileId, request.mandateId(),
                        "{\"stage\":\"ingest\",\"document_version_id\":" + documentVersionId + "}");

                db.connection().commit();
                return new IngestResult(documentId, documentVersionId, sourceFileId, parseRunId,
                        fileHash, file.getFileName().toString(), fileExt, fileRole, probe.pageCount(),
                        probe.ocrRequired(), false, blobPath, dbPath.toAbsolutePath().normalize());
            } catch (Exception e) {
                try {
                    db.connection().rollback();
                } catch (SQLException ignored) {
                    // keep the original failure
                }
                throw e;
            } finally {
                db.connection().setAutoCommit(true);
            }
        }
    }

    /**
     * Finds or creates the ingest run attached to a reused version's source file.
     *
     * @throws SQLException if the version has no source file or run persistence fails
     */
    private static long ensureParseRun(WorkspaceRepository repo,
            DocumentRepository.DocumentVersionRow row, long mandateId, String fileHash)
            throws SQLException {
        if (row.sourceFileId() != null) {
            return ensureParseRun(repo, row.sourceFileId(), mandateId,
                    "{\"stage\":\"ingest\",\"reused\":true,\"file_hash\":\"" + fileHash + "\"}");
        }
        throw new SQLException("document_version " + row.documentVersionId()
                + " has no source_file_id; cannot attach parse_run");
    }

    /**
     * Same bytes under another logical document is a duplicate, not new truth.
     * Record {@code document_relation.duplicate_of} (Architecture §6.2) so
     * reviewers see the twin instead of double-counting later.
     */
    private static void linkDuplicates(DocumentRepository docs, long documentVersionId,
            String fileHash) throws SQLException {
        for (Long other : docs.findVersionIdsByHash(fileHash)) {
            if (other == documentVersionId) {
                continue;
            }
            try {
                docs.insertRelation(other, documentVersionId, "duplicate_of",
                        "same sha256 " + fileHash.substring(0, 12));
            } catch (SQLException e) {
                if (e.getMessage() == null
                        || !e.getMessage().contains("UNIQUE constraint failed")) {
                    throw e;
                }
            }
        }
    }

    /**
     * One {@code parse_run} per {@code (source_file_id, parser_version, config_hash)}.
     * The same bytes can back several logical documents; they share this run.
     */
    private static long ensureParseRun(WorkspaceRepository repo, long sourceFileId,
            long mandateId, String metrics) throws SQLException {
        Long existing = repo.findParseRunId(sourceFileId, PARSER_VERSION, "m1");
        if (existing != null) {
            return existing;
        }
        try {
            return repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION, "m1",
                    Timestamps.now(), Timestamps.now(), "success", metrics);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                Long raced = repo.findParseRunId(sourceFileId, PARSER_VERSION, "m1");
                if (raced != null) {
                    return raced;
                }
            }
            throw e;
        }
    }

    private static long ensureSourceFile(WorkspaceRepository repo, long mandateId, Path file,
            String fileHash, DocumentFileKind kind, String rawMetadata, String now)
            throws SQLException {
        return com.resurgent.tev.parser.ingest.ParseRunIdentity.ensureSourceFile(
                mandateId, file.getFileName().toString(), fileHash, kind.sourceFileType(),
                PARSER_VERSION, rawMetadata, now, repo);
    }

    private static String writeMetadata(DocumentFileKind kind, Path file) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", kind.name().toLowerCase(Locale.ROOT));
        meta.put("file_name", file.getFileName().toString());
        meta.put("size_bytes", Files.size(file));
        // M3 document dates: the only file metadata date the workspace keeps. Weak by
        // nature (a download time, not the paper's date) — the assessment marks it as such.
        meta.put("file_modified_at", Files.getLastModifiedTime(file).toInstant().toString());
        return MAPPER.writeValueAsString(meta);
    }

    private static String sha256Hex(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record PageProbe(int pageCount, boolean ocrRequired) {}

    /**
     * Returns at least one page and an OCR estimate for PDFs; other kinds are
     * represented as one page requiring OCR without decoding the image.
     *
     * @throws IOException if the PDF cannot be opened or closed
     */
    private static PageProbe probePages(Path file, DocumentFileKind kind) throws IOException {
        if (kind == DocumentFileKind.PDF) {
            try (org.apache.pdfbox.pdmodel.PDDocument doc =
                    org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
                int pages = doc.getNumberOfPages();
                boolean needsOcr = hasNoTextLayer(doc);
                return new PageProbe(Math.max(pages, 1), needsOcr);
            }
        }
        // Single-raster images: no embedded text layer.
        return new PageProbe(1, true);
    }

    /**
     * Estimates OCR need from the first three pages: returns true for no pages,
     * fewer than 16 stripped text characters, or an IOException during text extraction.
     */
    private static boolean hasNoTextLayer(org.apache.pdfbox.pdmodel.PDDocument doc)
            throws IOException {
        if (doc.getNumberOfPages() == 0) {
            return true;
        }
        try {
            org.apache.pdfbox.text.PDFTextStripper stripper =
                    new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(3, doc.getNumberOfPages()));
            String text = stripper.getText(doc);
            return text == null || text.strip().length() < 16;
        } catch (IOException e) {
            return true;
        }
    }
}
