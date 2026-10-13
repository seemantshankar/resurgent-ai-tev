package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.ingest.BlobStore;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc extract}: schema-guided field extract into fact/fact_evidence/provenance_doc.
 */
@Command(name = "extract",
        description = "Extract pack fields from a document version into citable facts")
public final class DocExtractCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--version", required = true, description = "document_version_id")
    long documentVersionId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--pack", description = "Pack code (default: classified pack)")
    String pack;

    @Option(names = "--extractor", description = "llama-extract|fixture (default: config/env)")
    String extractor;

    @Option(names = "--pages", description = "Page range, e.g. 1-5,8")
    String pages;

    @Option(names = "--tier",
            description = "LlamaParse tier override: fast|cost_effective|agentic|agentic_plus"
                    + " (default: auto from layout)")
    String tier;

    @Option(names = "--no-parse",
            description = "Skip the LlamaParse reuse step; upload the original to extract")
    boolean noParse;

    @Option(names = "--blob-root", description = "Blob store root (default: <db-parent>/blobs)")
    Path blobRoot;

    @Spec
    CommandSpec spec;

    /** Runs extract and prints pack, extractor, parse tier, fact counts, and parse_run id. */
    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            BlobStore blobs = blobRoot != null
                    ? new BlobStore(blobRoot)
                    : BlobStore.besideDatabase(db);
            var result = com.resurgent.tev.doc.extract.DocExtractRunner.run(
                    mandateId, documentVersionId, db, blobs, pack, extractor, pages, tier, noParse);
            out.printf("Extracted with pack %s via %s%n", result.packIdentity(), result.extractorId());
            if (result.parseTier() != null) {
                out.printf("parse tier: %s%n", result.parseTier());
            }
            out.printf("facts written: %d (missing required: %d, ambiguous: %d)%n",
                    result.factsWritten(), result.missingRequired(), result.ambiguousCount());
            out.printf("parse_run: %d%n", result.parseRunId());
            return result.partial() ? 0 : 0;
        } catch (IllegalArgumentException e) {
            err.println("extract rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("extract failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }
}
