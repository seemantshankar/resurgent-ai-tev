package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * M2 extract orchestration (S2.5, S2.7): parse_run identity, schema-guided
 * {@link ExtractClient}, deterministic normalization, derived net-worth, quotation
 * line items, and transactional fact persistence. Re-extract with the same identity
 * replaces the fact set — never duplicates.
 */
public final class ExtractService {

    /** Parser version stamped on extract {@code parse_run}s. */
    public static final String PARSER_VERSION = "0.1.0-doc-extract";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Result of one extract pass. */
    public record ExtractOutcome(
            long parseRunId,
            long documentVersionId,
            String status,
            int factsTotal,
            int fieldsMissing,
            int fieldsAmbiguous,
            int derivedCount,
            int lineItemCount,
            String message) {}

    /**
     * Extracts pack fields for a document version and replaces its fact set.
     *
     * <p>parse_run identity is {@code (source_file_id, PARSER_VERSION, config_hash)} with
     * {@code config_hash = "extract:" + pack.code + "@" + pack.version + ":" + bundleVersion}.
     * Failures mark the run {@code failed} and leave no half-written facts.
     *
     * @param dbPath workspace database
     * @param documentVersionId version to extract
     * @param mandateId mandate scope
     * @param pack doc type pack (field keys, paths, tables)
     * @param client schema-guided extractor
     * @param extractorBundleVersion extractor bundle version (part of config_hash)
     * @return outcome with status {@code success} | {@code partial} | {@code failed}
     * @throws Exception on workspace or persistence failure
     */
    public ExtractOutcome extract(Path dbPath, long documentVersionId, long mandateId,
            DocTypePack pack, ExtractClient client, String extractorBundleVersion)
            throws Exception {
        return extract(dbPath, documentVersionId, mandateId, pack, client,
                extractorBundleVersion, null, null);
    }

    /**
     * Same as {@link #extract(Path, long, long, DocTypePack, ExtractClient, String)} with
     * optional extract instructions and an existing parse_run override (bind/extract reuse).
     *
     * @param instructions free-text instructions for the extractor (may be null)
     * @param options adapter options (may be null)
     */
    public ExtractOutcome extract(Path dbPath, long documentVersionId, long mandateId,
            DocTypePack pack, ExtractClient client, String extractorBundleVersion,
            String instructions, Map<String, String> options) throws Exception {
        String configHash = "extract:" + pack.code() + "@" + pack.version() + ":"
                + extractorBundleVersion;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            FactRepository facts = new FactRepository(db.connection());

            DocumentRepository.DocumentVersionRow version =
                    docs.findVersion(documentVersionId).orElseThrow(
                            () -> new IllegalArgumentException(
                                    "unknown document_version " + documentVersionId));
            if (version.sourceFileId() == null) {
                throw new IllegalStateException("document_version " + documentVersionId
                        + " has no source_file_id; cannot create extract parse_run");
            }

            long parseRunId = ensureExtractRun(repo, version.sourceFileId(), mandateId, configHash);

            JsonNode schema = pack.toExtractSchema();
            ExtractRequest request = buildRequest(dbPath, version, pack, instructions, options);

            ExtractResult result;
            try {
                result = client.extractFieldSchema(request, schema);
            } catch (ExtractException e) {
                markRunFailed(repo, parseRunId, e.getMessage());
                return new ExtractOutcome(parseRunId, documentVersionId, "failed",
                        0, 0, 0, 0, 0, e.getMessage());
            }

            List<FactCandidate> candidates = normalizeAll(result.facts());
            List<FactRepository.FactWrite> writes =
                    buildFactWrites(pack, candidates, result, client.id());

            int derivedCount = (int) writes.stream()
                    .filter(w -> "derived".equals(w.extractionMethod())).count();
            int lineItemCount = (int) writes.stream()
                    .filter(w -> w.lineKey() != null || "line_items".equals(w.fieldKey())).count();

            boolean empty = writes.isEmpty();
            String status = empty ? "partial" : "success";
            String message = empty ? "extractor returned no fields" : "ok";

            db.connection().setAutoCommit(false);
            try {
                // A re-run replaces the whole doc-extract stage: clear prior usage/stats so a
                // model or stat the new run did not produce does not linger.
                repo.deleteStageStats(parseRunId, "doc-extract");
                FactRepository.ReplaceStats stats =
                        facts.replaceFacts(parseRunId, documentVersionId, writes);
                int fieldsMissing = countMissing(pack, writes);
                int fieldsAmbiguous = countAmbiguous(writes);

                facts.recordRunStat(parseRunId, "doc-extract", "facts_total",
                        (double) stats.factsInserted(), null);
                facts.recordRunStat(parseRunId, "doc-extract", "facts_bound", 0d, null);
                facts.recordRunStat(parseRunId, "doc-extract", "fields_missing",
                        (double) fieldsMissing, null);
                facts.recordRunStat(parseRunId, "doc-extract", "fields_ambiguous",
                        (double) fieldsAmbiguous, null);
                facts.recordRunStat(parseRunId, "doc-extract", "quotes_resolved",
                        (double) stats.quotesResolved(), null);
                facts.recordRunStat(parseRunId, "doc-extract", "quotes_missing",
                        (double) stats.quotesMissing(), null);

                recordUsage(repo, parseRunId, client.id(), result.stats(), options);

                if (!empty) {
                    facts.updateVersionIngestStatus(documentVersionId, "extracted");
                }

                String auditPayload = MAPPER.writeValueAsString(Map.of(
                        "document_version_id", documentVersionId,
                        "pack", pack.identity(),
                        "extractor", client.id(),
                        "facts", stats.factsInserted(),
                        "status", status));
                facts.insertAuditLog(parseRunId, "extract_completed", Timestamps.now(),
                        auditPayload, empty ? "warn" : "info");

                Map<String, Object> resultMeta = new LinkedHashMap<>();
                resultMeta.put("facts", stats.factsInserted());
                resultMeta.put("quotes_resolved", stats.quotesResolved());
                resultMeta.put("quotes_missing", stats.quotesMissing());
                resultMeta.put("stage", "doc-extract");
                if (options != null) {
                    putIfPresent(resultMeta, LlamaExtractAdapter.KEY_PARSE_JOB_ID,
                            options.get(LlamaExtractAdapter.KEY_PARSE_JOB_ID));
                    putIfPresent(resultMeta, LlamaExtractAdapter.KEY_PARSE_TIER,
                            options.get(LlamaExtractAdapter.KEY_PARSE_TIER));
                }
                repo.updateParseRunResult(parseRunId, Timestamps.now(), status,
                        MAPPER.writeValueAsString(resultMeta));

                db.connection().commit();
                return new ExtractOutcome(parseRunId, documentVersionId, status,
                        stats.factsInserted(), fieldsMissing, fieldsAmbiguous,
                        derivedCount, lineItemCount, message);
            } catch (Exception e) {
                try {
                    db.connection().rollback();
                } catch (Exception ignored) {
                    // keep the original failure
                }
                try {
                    db.connection().setAutoCommit(true);
                } catch (Exception ignored) {
                    // keep the original failure
                }
                markRunFailed(repo, parseRunId, e.getMessage());
                throw e;
            } finally {
                try {
                    db.connection().setAutoCommit(true);
                } catch (Exception ignored) {
                    // connection may already be released
                }
            }
        }
    }

    /**
     * Builds the extract config hash identity fragment.
     *
     * @param pack pack being extracted
     * @param extractorBundleVersion extractor bundle version
     * @return {@code extract:code@version:bundle}
     */
    public static String configHash(DocTypePack pack, String extractorBundleVersion) {
        return "extract:" + pack.code() + "@" + pack.version() + ":" + extractorBundleVersion;
    }

    // --- pipeline steps ---

    static List<FactCandidate> normalizeAll(List<FactCandidate> input) {
        List<FactCandidate> out = new ArrayList<>();
        if (input != null) {
            for (FactCandidate c : input) {
                out.add(CandidateNormalizer.normalize(c));
            }
        }
        return out;
    }

    /**
     * Maps normalized candidates plus quotation {@code line_items} into fact writes,
     * including the derived net-worth fact when the pack can support it.
     */
    static List<FactRepository.FactWrite> buildFactWrites(DocTypePack pack,
            List<FactCandidate> candidates, ExtractResult result, String extractorId) {
        List<FactRepository.FactWrite> writes = new ArrayList<>();
        List<FactCandidate> stated = new ArrayList<>();
        List<FactCandidate> lineCandidates = new ArrayList<>();
        for (FactCandidate c : candidates) {
            if ("line_items".equals(c.key())) {
                lineCandidates.add(c);
                continue;
            }
            stated.add(c);
            writes.add(toWrite(c, pack, extractorId));
        }

        // Quotation line items: one fact per line + parent totals (already in candidates).
        String vendor = findSubject(pack, stated);
        writes.addAll(lineItemWrites(pack, lineCandidates, result, vendor, extractorId));

        // Derived net worth when the pack can compute it and none was stated.
        FactRepository.FactWrite derived =
                derivedNetWorth(pack, stated, extractorId);
        if (derived != null) {
            writes.add(derived);
        }
        return writes;
    }

    private static FactRepository.FactWrite toWrite(FactCandidate c, DocTypePack pack,
            String extractorId) {
        String path = null;
        String pathRoot = null;
        Optional<PackField> field = pack.field(c.key());
        if (field.isPresent()) {
            path = field.get().path();
            pathRoot = field.get().pathRootWire();
        }
        return new FactRepository.FactWrite(
                c.key(),
                path,
                pathRoot,
                c.subjectEntity(),
                c.valueKind().wireName(),
                c.valueText(),
                c.valueNumber(),
                c.valueDate(),
                c.unit(),
                c.currency(),
                c.scale(),
                c.absoluteAmount(),
                null,
                c.extractionMethod(),
                extractorId,
                c.confidence(),
                c.statedOrDerived(),
                c.lineKey(),
                null,
                c.citations(),
                c.derivedFromKeys());
    }

    private static String findSubject(DocTypePack pack, List<FactCandidate> stated) {
        for (FactCandidate c : stated) {
            if (c.subjectEntity() != null && !c.subjectEntity().isBlank()) {
                return c.subjectEntity();
            }
        }
        for (String key : new String[] {"vendor_name", "subject_name"}) {
            for (FactCandidate c : stated) {
                if (key.equals(c.key()) && c.valueText() != null) {
                    return c.valueText();
                }
            }
        }
        return null;
    }

    /**
     * One fact per line item: path from the pack {@code line_items} table, subject = vendor,
     * {@code line_key} = description when present (also used as {@code value_text} when the
     * line has no numeric amount). Accepts both {@code FactCandidate} rows
     * ({@code key='line_items'}) and a raw {@code line_items[]} payload array.
     */
    static List<FactRepository.FactWrite> lineItemWrites(DocTypePack pack,
            List<FactCandidate> lineCandidates, ExtractResult result, String vendor,
            String extractorId) {
        List<FactRepository.FactWrite> lines = new ArrayList<>();
        String tablePath = null;
        String tableRoot = null;
        for (Map<String, Object> table : pack.tables()) {
            if ("line_items".equals(String.valueOf(table.get("name")))) {
                tablePath = String.valueOf(table.get("path"));
                Object root = table.get("path_root");
                tableRoot = root == null ? "economic" : String.valueOf(root);
                break;
            }
        }
        if (tablePath == null) {
            return lines;
        }
        String path = tablePath;
        String rootWire = tableRoot.toLowerCase(Locale.ROOT);

        for (FactCandidate c : lineCandidates) {
            String description = c.lineKey() != null ? c.lineKey() : c.valueText();
            FactCandidate line = new FactCandidate("line_items", ValueKind.MONEY);
            line.subjectEntity(c.subjectEntity() != null ? c.subjectEntity() : vendor);
            line.lineKey(description);
            line.extractionMethod(c.extractionMethod());
            line.statedOrDerived(c.statedOrDerived());
            line.confidence(c.confidence());
            if (c.valueNumber() != null) {
                line.valueNumber(c.valueNumber());
                line.valueText(c.valueText() != null ? c.valueText() : String.valueOf(c.valueNumber()));
            } else if (c.valueText() != null) {
                line.valueText(c.valueText());
            } else if (description != null) {
                line.valueText(description);
            }
            line.currency(c.currency());
            line.scale(c.scale());
            line.absoluteAmount(c.absoluteAmount());
            CandidateNormalizer.normalize(line);
            lines.add(new FactRepository.FactWrite(
                    "line_items",
                    path,
                    rootWire,
                    line.subjectEntity(),
                    line.valueKind().wireName(),
                    line.valueText(),
                    line.valueNumber(),
                    line.valueDate(),
                    line.unit(),
                    line.currency(),
                    line.scale(),
                    line.absoluteAmount(),
                    null,
                    line.extractionMethod(),
                    extractorId,
                    line.confidence(),
                    line.statedOrDerived(),
                    description,
                    null,
                    c.citations(),
                    List.of()));
        }

        if (result == null || result.payload() == null) {
            return lines;
        }
        JsonNode items = result.payload().get("line_items");
        if (items == null || !items.isArray()) {
            return lines;
        }
        for (JsonNode item : items) {
            String description = textOrNull(item, "description");
            String amountText = textOrNull(item, "amount");
            String qtyText = textOrNull(item, "qty");
            String rateText = textOrNull(item, "unit_rate");
            FactCandidate line = new FactCandidate("line_items", ValueKind.MONEY);
            line.subjectEntity(vendor);
            line.lineKey(description);
            line.extractionMethod("llm_field");
            line.statedOrDerived("stated");
            if (amountText != null) {
                line.valueText(amountText);
            } else if (rateText != null) {
                line.valueText(rateText);
            } else if (qtyText != null) {
                line.valueText(qtyText);
            } else if (description != null) {
                line.valueText(description);
            }
            CandidateNormalizer.normalize(line);
            lines.add(new FactRepository.FactWrite(
                    "line_items",
                    path,
                    rootWire,
                    vendor,
                    line.valueKind().wireName(),
                    line.valueText(),
                    line.valueNumber(),
                    line.valueDate(),
                    line.unit(),
                    line.currency(),
                    line.scale(),
                    line.absoluteAmount(),
                    null,
                    "llm_field",
                    extractorId,
                    line.confidence(),
                    "stated",
                    description,
                    null,
                    List.of(),
                    List.of()));
        }
        return lines;
    }

    /**
     * Derived net worth: assets_total − liabilities_total when the pack has those leaves
     * and no stated net_worth. {@code extraction_method='derived'}; evidence is
     * {@code derived_from} to the parent facts.
     */
    static FactRepository.FactWrite derivedNetWorth(DocTypePack pack,
            List<FactCandidate> stated, String extractorId) {
        if (pack.field("assets_total").isEmpty() || pack.field("liabilities_total").isEmpty()) {
            return null;
        }
        if (pack.field("net_worth").isEmpty()) {
            return null;
        }
        for (FactCandidate c : stated) {
            if ("net_worth".equals(c.key()) && hasValue(c)) {
                return null;
            }
        }
        FactCandidate assets = find(stated, "assets_total");
        FactCandidate liabilities = find(stated, "liabilities_total");
        if (assets == null || liabilities == null) {
            return null;
        }
        BigDecimal a = absolute(assets);
        BigDecimal l = absolute(liabilities);
        if (a == null || l == null) {
            return null;
        }
        BigDecimal net = a.subtract(l);
        FactCandidate derived = new FactCandidate("net_worth", ValueKind.MONEY);
        derived.absoluteAmount(net.toPlainString());
        derived.currency(assets.currency() != null ? assets.currency() : liabilities.currency());
        derived.scale(assets.scale() != null ? assets.scale() : liabilities.scale());
        derived.valueNumber(net.doubleValue());
        derived.valueText(net.toPlainString());
        derived.confidence(minConfidence(assets, liabilities));
        derived.extractionMethod("derived");
        derived.statedOrDerived("derived");
        derived.addDerivedFromKey("assets_total");
        derived.addDerivedFromKey("liabilities_total");
        PackField netWorth = pack.field("net_worth").orElseThrow();
        return new FactRepository.FactWrite(
                derived.key(),
                netWorth.path(),
                netWorth.pathRootWire(),
                derived.subjectEntity(),
                derived.valueKind().wireName(),
                derived.valueText(),
                derived.valueNumber(),
                derived.valueDate(),
                derived.unit(),
                derived.currency(),
                derived.scale(),
                derived.absoluteAmount(),
                null,
                derived.extractionMethod(),
                extractorId,
                derived.confidence(),
                derived.statedOrDerived(),
                derived.lineKey(),
                null,
                List.of(),
                derived.derivedFromKeys());
    }

    private static boolean hasValue(FactCandidate c) {
        return c.valueText() != null || c.valueNumber() != null || c.absoluteAmount() != null;
    }

    private static FactCandidate find(List<FactCandidate> stated, String key) {
        for (FactCandidate c : stated) {
            if (key.equals(c.key()) && hasValue(c)) {
                return c;
            }
        }
        return null;
    }

    private static BigDecimal absolute(FactCandidate c) {
        if (c.absoluteAmount() != null && !c.absoluteAmount().isBlank()) {
            try {
                return new BigDecimal(c.absoluteAmount().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        if (c.valueNumber() != null) {
            return BigDecimal.valueOf(c.valueNumber());
        }
        return null;
    }

    private static Double minConfidence(FactCandidate a, FactCandidate b) {
        if (a.confidence() == null && b.confidence() == null) {
            return null;
        }
        if (a.confidence() == null) {
            return b.confidence();
        }
        if (b.confidence() == null) {
            return a.confidence();
        }
        return Math.min(a.confidence(), b.confidence());
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * Records extractor spend (stage {@code doc-extract}) so LlamaParse/Extract credits are
     * attributable. Two rows may be written:
     * <ul>
     *   <li>the extractor's own call under {@code client.id()} (extract_credits when a parse
     *       job backed it, else the total credits);</li>
     *   <li>the reused LlamaParse job under {@code llama-parse:<tier>} when
     *       {@code llama_parse_job_id} is set — its credits are billed separately.</li>
     * </ul>
     * Credit totals are stored in the {@code cost_usd} slot (LlamaCloud reports credits, not
     * USD); {@code cost_missing} marks a row whose credits were not recorded yet.
     */
    private static void recordUsage(WorkspaceRepository repo, long parseRunId, String modelId,
            ExtractStats stats, Map<String, String> options) throws Exception {
        if (stats != null && (stats.calls() > 0 || stats.costUsd() != null
                || stats.promptTokens() > 0 || stats.completionTokens() > 0)) {
            repo.recordLlmUsage(parseRunId, "doc-extract", modelId, stats.calls(),
                    stats.failedCalls(), 0, stats.promptTokens(), stats.completionTokens(),
                    stats.costUsd(), stats.costUsd() == null ? 1 : 0, stats.latencyMsTotal());
        }
        if (options == null) {
            return;
        }
        String parseJobId = options.get(LlamaExtractAdapter.KEY_PARSE_JOB_ID);
        if (parseJobId == null || parseJobId.isBlank()) {
            return;
        }
        String tier = options.get(LlamaExtractAdapter.KEY_PARSE_TIER);
        String parseModel = "llama-parse" + (tier == null || tier.isBlank() ? "" : ":" + tier);
        Double credits = parseDoubleOrNull(options.get(LlamaExtractAdapter.KEY_PARSE_CREDITS));
        long latency = parseLongOrZero(options.get(LlamaExtractAdapter.KEY_PARSE_LATENCY_MS));
        repo.recordLlmUsage(parseRunId, "doc-extract", parseModel, 1, 0, 0, 0, 0,
                credits, credits == null ? 1 : 0, latency);
    }

    private static Double parseDoubleOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLongOrZero(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String text = v.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static int countMissing(DocTypePack pack, List<FactRepository.FactWrite> writes) {
        int missing = 0;
        for (PackField field : pack.requiredFields()) {
            boolean present = writes.stream().anyMatch(w -> field.key().equals(w.fieldKey())
                    && (w.valueText() != null || w.valueNumber() != null
                            || w.absoluteAmount() != null || w.valueDate() != null));
            if (!present) {
                missing++;
            }
        }
        return missing;
    }

    private static int countAmbiguous(List<FactRepository.FactWrite> writes) {
        int n = 0;
        for (FactRepository.FactWrite w : writes) {
            if (w.confidence() != null && w.confidence() < 0.5) {
                n++;
            }
        }
        return n;
    }

    private static ExtractRequest buildRequest(Path dbPath,
            DocumentRepository.DocumentVersionRow version, DocTypePack pack,
            String instructions, Map<String, String> options) {
        BlobStore blobs = BlobStore.besideDatabase(dbPath);
        Path bundleDir = blobs.root().resolve(version.fileHash());
        Path original = blobs.originalPath(version.fileHash(), version.fileExt());
        List<Integer> pages = new ArrayList<>();
        int pageCount = version.pageCount() == null ? 0 : version.pageCount();
        for (int i = 1; i <= pageCount; i++) {
            pages.add(i);
        }
        String instructionsText = instructions != null ? instructions
                : ("Extract fields for pack " + pack.identity()
                        + ". Return exact quotes from the source.");
        return new ExtractRequest(bundleDir, original, pages, null, pack.code(),
                instructionsText, options);
    }

    private static long ensureExtractRun(WorkspaceRepository repo, long sourceFileId,
            long mandateId, String configHash) throws Exception {
        Long existing = repo.findParseRunId(sourceFileId, PARSER_VERSION, configHash);
        if (existing != null) {
            return existing;
        }
        try {
            return repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION, configHash,
                    Timestamps.now(), Timestamps.now(), "running", null);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                Long raced = repo.findParseRunId(sourceFileId, PARSER_VERSION, configHash);
                if (raced != null) {
                    return raced;
                }
            }
            throw e;
        }
    }

    private static void markRunFailed(WorkspaceRepository repo, long parseRunId, String message)
            throws Exception {
        repo.updateParseRunResult(parseRunId, Timestamps.now(), "failed",
                message == null ? null : "\"" + message.replace("\"", "'") + "\"");
        // Best-effort audit so the failure is not silent even outside the tx.
        try {
            new FactRepository(repo.connection()).insertAuditLog(parseRunId,
                    "extract_completed", Timestamps.now(),
                    "{\"status\":\"failed\",\"message\":\""
                            + (message == null ? "" : message.replace("\"", "'"))
                            + "\"}",
                    "error");
        } catch (Exception ignored) {
            // failure marking must not mask the original error
        }
    }
}
