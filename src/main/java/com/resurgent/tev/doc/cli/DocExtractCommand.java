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

    @Option(names = "--extractor",
            description = "doc-llm-extract|fixture|llama-extract (default: doc-llm-extract)")
    String extractor;

    @Option(names = "--pages",
            description = "1-based page range, e.g. 1-5,8 (default: all pages)")
    String pages;

    @Option(names = "--tier",
            description = "LlamaParse tier override: fast|cost_effective|agentic|agentic_plus"
                    + " (default: auto from layout)")
    String tier;

    @Option(names = "--no-parse",
            description = "Skip the LlamaParse reuse step; upload the original to extract")
    boolean noParse;

    @Option(names = "--no-auto-induce",
            description = "Do not induce a pack when the version is still unknown")
    boolean noAutoInduce;

    @Option(names = "--blob-root", description = "Blob store root (default: <db-parent>/blobs)")
    Path blobRoot;

    @Option(names = "--platform",
            description = "Platform registry. Created when this path is new.")
    Path platform;

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
                    mandateId, documentVersionId, db, blobs, pack, extractor, pages, tier,
                    noParse, !noAutoInduce, platform);
            out.printf("Extracted with pack %s via %s%n", result.packIdentity(), result.extractorId());
            if (result.parseTier() != null) {
                out.printf("parse tier: %s%n", result.parseTier());
            }
            out.printf("facts written: %d (missing required: %d, ambiguous: %d)%n",
                    result.factsWritten(), result.missingRequired(), result.ambiguousCount());
            out.printf("parse_run: %d%n", result.parseRunId());
            if (!"success".equals(result.status())) {
                err.println("warning: extract " + result.status() + ": " + result.message());
            }
            int exit = exitCode(result.status());
            if (exit == 0) {
                // Processing new documents ends by re-running the assessment (M3-01).
                reassess(mandateId, db, out, err);
            }
            return exit;
        } catch (IllegalArgumentException e) {
            err.println("extract rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("extract failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }

    /**
     * Re-runs the mandate assessment after a successful extract. A failed assessment is
     * a warning, not an extract failure: the extracted facts are already persisted.
     */
    private static void reassess(long mandateId, java.nio.file.Path db,
            java.io.PrintWriter out, java.io.PrintWriter err) {
        try {
            var assessment = com.resurgent.tev.doc.assess.DocAssessRunner.run(mandateId, db);
            for (String line : assessment.summaryLines()) {
                out.println("assessment: " + line);
            }
        } catch (Exception e) {
            err.println("warning: assessment re-run failed: "
                    + (e.getMessage() != null ? e.getMessage() : e));
        }
    }

    /**
     * Exit code for an extract outcome (spec S2.8): {@code 0} success — including
     * partial, which prints warnings — and {@code 1} runtime failure. Invalid
     * arguments/pack exit {@code 3} in {@link #call()}.
     *
     * @param status extract outcome status ({@code success} | {@code partial} | {@code failed})
     * @return {@code 1} when failed, else {@code 0}
     */
    static int exitCode(String status) {
        return "failed".equals(status) ? 1 : 0;
    }
}
