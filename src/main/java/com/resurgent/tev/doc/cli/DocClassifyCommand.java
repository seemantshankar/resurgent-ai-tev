package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.ingest.BlobStore;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc classify}: attach doc_type_code + pack selection to a document version.
 */
@Command(name = "classify",
        description = "Classify a document version into a doc type pack (or unknown)")
public final class DocClassifyCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--version", required = true, description = "document_version_id")
    long documentVersionId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--pack-dir", description = "Extra pack directory (seed packs load from classpath)")
    Path packDir;

    @Option(names = "--industry", description = "auto|<tag>")
    String industry;

    @Option(names = "--force", description = "Overwrite an existing classification")
    boolean force;

    @Option(names = "--no-auto-induce",
            description = "Leave an unknown document unknown instead of inducing a pack")
    boolean noAutoInduce;

    @Option(names = "--blob-root", description = "Blob store root (default: <db-parent>/blobs)")
    Path blobRoot;

    @Spec
    CommandSpec spec;

    /** Runs classification and prints the chosen pack or {@code unknown}. */
    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            BlobStore blobs = blobRoot != null
                    ? new BlobStore(blobRoot)
                    : BlobStore.besideDatabase(db);
            var result = com.resurgent.tev.doc.classify.DocClassifyRunner.run(
                    mandateId, documentVersionId, db, blobs, packDir, industry, force,
                    !noAutoInduce);
            String llm = result.liveModel() ? " (LLM tie-break available)" : "";
            if ("unknown".equals(result.docTypeCode())) {
                out.printf("Classified version %d as unknown (confidence %.2f, %s)%s.%n",
                        documentVersionId, result.confidence(), result.classifier(), llm);
            } else {
                out.printf("Classified version %d as %s pack %s@%s (confidence %.2f, %s)%s.%n",
                        documentVersionId, result.docTypeCode(),
                        result.packCode(), result.packVersion(), result.confidence(),
                        result.classifier(), llm);
            }
            for (String reason : result.reasons()) {
                out.printf("  reason: %s%n", reason);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("classify rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("classify failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }
}
