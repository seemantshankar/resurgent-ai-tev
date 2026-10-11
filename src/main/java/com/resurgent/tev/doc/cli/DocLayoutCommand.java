package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code tev-doc layout}: write the digital-text layout graph for a document version. */
@Command(name = "layout",
        description = "Extract digital-text blocks/spans/tables for a document version")
public final class DocLayoutCommand implements Callable<Integer> {

    @Option(names = "--version", required = true, description = "document_version_id")
    long documentVersionId;

    @Option(names = "--mandate", description = "Mandate id (for logging)")
    Long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--blob-root",
            description = "Blob store root (default: <db-parent>/blobs)")
    Path blobRoot;

    @Spec
    CommandSpec spec;

    /**
     * Ensures layout for the configured version and prints the result.
     *
     * @return 0 on success, 3 for an IllegalArgumentException from the operation,
     *         or 1 for another caught exception
     */
    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            BlobStore blobs = blobRoot != null
                    ? new BlobStore(blobRoot)
                    : BlobStore.besideDatabase(db);
            DocumentLayoutService.LayoutResult result = new DocumentLayoutService().layout(
                    documentVersionId, db, blobs);
            out.printf("Layout complete for document_version %d: %d pages, %d blocks,"
                            + " %d spans, %d tables (extractor %s, ocr_required=%s).%n",
                    result.documentVersionId(), result.pageCount(), result.blockCount(),
                    result.spanCount(), result.tableCount(), result.extractor(),
                    result.ocrRequired());
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("layout rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("layout failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }
}
