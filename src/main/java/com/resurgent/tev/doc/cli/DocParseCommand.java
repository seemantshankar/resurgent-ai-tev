package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.extract.ExtractException;
import com.resurgent.tev.doc.extract.LlamaParseClient;
import com.resurgent.tev.doc.extract.ParseService;
import com.resurgent.tev.doc.extract.ParseTier;
import com.resurgent.tev.doc.ingest.BlobStore;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc parse}: run LlamaParse on a document version with the tier chosen from
 * its layout, and emit the cleaned markdown. Use {@code --out} to write one file per page.
 */
@Command(name = "parse",
        description = "Parse a document version into clean markdown (tier from layout)")
public final class DocParseCommand implements Callable<Integer> {

    @Option(names = "--version", required = true, description = "document_version_id")
    long documentVersionId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--tier", description = "fast|cost_effective|agentic|agentic_plus (default: auto)")
    String tier;

    @Option(names = "--job", description = "Re-fetch an existing LlamaParse job id instead of parsing")
    String jobId;

    @Option(names = "--out", description = "Write cleaned markdown as pNNN.md files into this directory")
    Path out;

    @Option(names = "--blob-root", description = "Blob store root (default: <db-parent>/blobs)")
    Path blobRoot;

    @Spec
    CommandSpec spec;

    /** Runs parse and prints tier, job id, page count, and (optionally) writes pages. */
    @Override
    public Integer call() {
        var outStream = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            LlamaParseClient client = new LlamaParseClient();
            ParseTier override = tier == null || tier.isBlank() ? null : ParseTier.fromWire(tier);
            BlobStore blobs = blobRoot != null
                    ? new BlobStore(blobRoot)
                    : BlobStore.besideDatabase(db);

            if (jobId != null && !jobId.isBlank()) {
                ParseService.ParseOutcome outcome = ParseService.reFetch(
                        db, documentVersionId, override, jobId.trim(), client);
                outStream.printf("Fetched parse job %s and persisted %d page(s) for version %d%n",
                        outcome.jobId(), outcome.pageMarkdown().size(), documentVersionId);
                if (out != null) {
                    writePages(outcome, out, outStream);
                }
                return 0;
            }

            ParseService.ParseOutcome result =
                    ParseService.parse(db, documentVersionId, override, client, blobs);
            outStream.printf("Parsed version %d with tier %s (job %s), %d page(s)%n",
                    documentVersionId, result.tier().wireName(), result.jobId(),
                    result.pageMarkdown().size());
            if (out != null) {
                writePages(result, out, outStream);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("parse rejected: " + e.getMessage());
            return 3;
        } catch (ExtractException e) {
            err.println("parse failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        } catch (Exception e) {
            err.println("parse failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }

    private void writePages(ParseService.ParseOutcome outcome, Path dir, java.io.PrintWriter out)
            throws Exception {
        List<Path> written = ParseService.writePages(outcome, dir);
        out.printf("wrote %d page file(s) to %s%n", written.size(), dir.toAbsolutePath());
    }
}
