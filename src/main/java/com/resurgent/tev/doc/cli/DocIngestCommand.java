package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code tev-doc ingest}: land a non-Excel document in the workspace. */
@Command(name = "ingest",
        description = "Ingest a PDF/image document into a SQLite workspace (blob + pages)")
public final class DocIngestCommand implements Callable<Integer> {

    @Option(names = "--file", required = true, description = "Path to the PDF or image")
    Path file;

    @Option(names = "--mandate", required = true, description = "Mandate id this document belongs to")
    long mandateId;

    @Option(names = "--logical-doc", required = true,
            description = "Logical document name ('PPA', 'Promoter NW - Sharma')")
    String logicalDoc;

    @Option(names = "--label", description = "Version label (default: v1)")
    String label;

    @Option(names = "--file-role",
            description = "fm_workbook|dpr|agreement|certificate|quotation|technical_report|regulatory|identity|other")
    String fileRole;

    @Option(names = "--doc-family", description = "Document family (agreement, certificate, ...)")
    String docFamily;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--blob-root",
            description = "Blob store root (default: <db-parent>/blobs)")
    Path blobRoot;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            BlobStore blobs = blobRoot != null
                    ? new BlobStore(blobRoot)
                    : BlobStore.besideDatabase(db);
            DocumentIngestService.IngestResult result = new DocumentIngestService().ingest(
                    new DocumentIngestService.IngestRequest(
                            file, mandateId, logicalDoc, label, fileRole, docFamily),
                    db, blobs);
            if (result.reused()) {
                out.printf("Reused document version %d for %s (sha256 %s, %d pages).%n",
                        result.documentVersionId(), result.fileName(),
                        result.fileHash().substring(0, 12), result.pageCount());
            } else {
                out.printf("Ingested %s as document %d version %d (label %s, role %s,"
                                + " %d pages, ocr_required=%s, sha256 %s).%n",
                        result.fileName(), result.documentId(), result.documentVersionId(),
                        label == null ? "v1" : label, result.fileRole(), result.pageCount(),
                        result.ocrRequired(), result.fileHash().substring(0, 12));
            }
            out.printf("Blob: %s%n", result.blobPath());
            out.printf("Next: tev-doc layout --version %d --db %s%n",
                    result.documentVersionId(), db);
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("ingest rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("ingest failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }
}
