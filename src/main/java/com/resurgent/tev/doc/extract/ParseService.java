package com.resurgent.tev.doc.extract;

import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Production Parse step: turns an ingested {@code document_version} into clean markdown
 * with the tier chosen from its M1 layout signals.
 *
 * <p>This is the wiring between ingest/layout and extract:
 * <ol>
 *   <li>resolve the immutable original blob for the version;</li>
 *   <li>pick a {@link ParseTier} from {@link LayoutSignals} + {@link ParseTierSelector}
 *       (identity cards, scans, complex tables → agentic);</li>
 *   <li>call {@link LlamaParseClient}, whose job id becomes the Extract v2
 *       {@code file_input} so extract reuses this parse instead of re-parsing;</li>
 *   <li>hand back the {@code ParseMarkdown}-cleaned per-page markdown.</li>
 * </ol>
 *
 * <p>Page provenance is structural: the returned page list is ordered, so downstream
 * citations map a value to {@code page_index} → {@code provenance_doc.page_id}. No
 * textual page marker is needed.
 */
public final class ParseService {

    private ParseService() {}

    /**
     * Parse outcome for one version.
     *
     * @param tier tier actually used
     * @param jobId LlamaParse job id (Extract {@code file_input})
     * @param original resolved original blob path
     * @param pageMarkdown cleaned markdown per page, in page order
     * @param pageText plain text per page, in page order
     * @param credits credits billed for the parse job ({@code job.usage.credits}); may be null
     * @param latencyMs wall-clock time spent in the parse call
     */
    public record ParseOutcome(
            ParseTier tier,
            String jobId,
            Path original,
            List<String> pageMarkdown,
            List<String> pageText,
            Double credits,
            long latencyMs) {

        /** Concatenated cleaned markdown (all pages). */
        public String markdown() {
            return String.join("\n\n", pageMarkdown);
        }

        /** Outcome with credits/latency unknown (used when re-fetching an existing job). */
        public ParseOutcome(ParseTier tier, String jobId, Path original,
                List<String> pageMarkdown, List<String> pageText) {
            this(tier, jobId, original, pageMarkdown, pageText, null, 0L);
        }
    }

    /**
     * Picks the tier for a version without calling the service.
     *
     * @param dbPath workspace database
     * @param documentVersionId version to inspect
     * @return recommended tier
     * @throws Exception on SQL failure
     */
    public static ParseTier selectTier(Path dbPath, long documentVersionId) throws Exception {
        ParseTierSelector.Signals signals = LayoutSignals.fromVersion(dbPath, documentVersionId);
        return ParseTierSelector.select(signals);
    }

    /**
     * Parses a version with an explicit tier, resolving its original blob beside the db.
     *
     * @param dbPath workspace database
     * @param documentVersionId version to parse
     * @param tierOverride explicit tier, or {@code null} to auto-select from layout
     * @param client configured LlamaParse client
     * @return parse outcome with cleaned markdown
     * @throws Exception on missing version/blob or parse failure
     */
    public static ParseOutcome parse(Path dbPath, long documentVersionId, ParseTier tierOverride,
            LlamaParseClient client) throws Exception {
        return parse(dbPath, documentVersionId, tierOverride, client,
                BlobStore.besideDatabase(dbPath));
    }

    /**
     * Parses a version with an explicit tier and blob store.
     *
     * @param dbPath workspace database
     * @param documentVersionId version to parse
     * @param tierOverride explicit tier, or {@code null} to auto-select from layout
     * @param client configured LlamaParse client
     * @param blobs blob store holding the original
     * @return parse outcome with cleaned markdown
     * @throws Exception on missing version/blob or parse failure
     */
    public static ParseOutcome parse(Path dbPath, long documentVersionId, ParseTier tierOverride,
            LlamaParseClient client, BlobStore blobs) throws Exception {
        DocumentRepository.DocumentVersionRow version = requireVersion(dbPath, documentVersionId);
        Path original = blobs.originalPath(version.fileHash(), version.fileExt());
        if (!Files.isRegularFile(original)) {
            throw new ExtractException("original blob not found for document_version "
                    + documentVersionId + ": " + original);
        }
        ParseTier tier = tierOverride != null ? tierOverride
                : selectTier(dbPath, documentVersionId);
        long startNanos = System.nanoTime();
        LlamaParseClient.ParseJob job = client.parse(original, tier);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        storePages(dbPath, documentVersionId, tier, job);
        return new ParseOutcome(tier, job.jobId(), original,
                job.pageMarkdown(), job.pageText(), job.credits(), latencyMs);
    }

    public static ParseOutcome reFetch(Path dbPath, long documentVersionId, ParseTier tier,
            String jobId, LlamaParseClient client) throws Exception {
        LlamaParseClient.ParseJob job = client.get(jobId);
        if (!"COMPLETED".equals(job.status())) {
            throw new ExtractException("LlamaParse job " + jobId + " ended " + job.status());
        }
        storePages(dbPath, documentVersionId, tier, job);
        return fromJob(tier, null, job);
    }

    public static void storePages(Path dbPath, long documentVersionId, ParseTier tier,
            LlamaParseClient.ParseJob job) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(dbPath)) {
            new CloudParsePageRepository(workspace.connection()).replacePages(
                    documentVersionId, job.jobId(), tier == null ? null : tier.wireName(),
                    job.pageMarkdown(), job.pageText());
        }
    }

    public static ParseOutcome fromJob(ParseTier tier, Path original,
            LlamaParseClient.ParseJob job) {
        return new ParseOutcome(tier, job.jobId(), original,
                job.pageMarkdown(), job.pageText(), job.credits(), 0L);
    }

    public static List<String> loadTextPages(Path dbPath, long documentVersionId)
            throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(dbPath)) {
            return new CloudParsePageRepository(workspace.connection())
                    .loadTextPages(documentVersionId);
        }
    }

    /**
     * Writes one {@code pNNN.md} per page into {@code outDir} (created if needed).
     *
     * @param outcome parse outcome
     * @param outDir directory to write into
     * @return the written files in page order
     * @throws Exception on I/O failure
     */
    public static List<Path> writePages(ParseOutcome outcome, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        List<Path> written = new ArrayList<>();
        List<String> pages = outcome.pageMarkdown();
        for (int i = 0; i < pages.size(); i++) {
            Path file = outDir.resolve(String.format(Locale.ROOT, "p%03d.md", i + 1));
            Files.writeString(file, pages.get(i));
            written.add(file);
        }
        return written;
    }

    private static DocumentRepository.DocumentVersionRow requireVersion(Path dbPath, long id)
            throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(dbPath)) {
            return new DocumentRepository(workspace.connection()).findVersion(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown document_version " + id));
        }
    }
}
