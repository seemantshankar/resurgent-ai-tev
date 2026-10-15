package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.BlockAnchor;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.db.AssessmentRepository.VersionRow;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code assess_profile} industry step (M3-05, issue #138): records the mandate's
 * industries, aggregating M2's per-document industry detection across every document
 * version. Several industries are allowed — a hybrid project yields two — and an
 * industry the installed packs have not seen is recorded as stated and marked new
 * instead of being forced into the nearest known one.
 *
 * <p>Deterministic first: the known industries are the installed packs'
 * {@code industry_tags} (data, not Java vocabulary; {@code general} and {@code hybrid}
 * are markers, not industries). The leading term of each known industry is looked for
 * in the document text and a text hit is recorded with its page. Only a document whose
 * pack declares no specific industry — or that is unclassified — gets one model read
 * of the industry; the model's answer is recorded even when it names an industry the
 * packs have never seen ({@code is_new_industry}).
 */
public final class IndustryScan {

    /** Pack tags that are markers, not industries: the generic and hybrid tags. */
    private static final Set<String> MARKERS = Set.of("general", "hybrid");

    private static final String SYSTEM = """
            You read ONE industry from one supporting document of a project-finance mandate.
            Reply with a single JSON object: {"industry": "<industry the document is about, \
            for example solar power, wind power, hotel or green hydrogen>", \
            "quote": "<verbatim text the industry was read from>"}.
            Name the industry the document itself describes. Never guess, and never force \
            it into the nearest industry you know.
            Reply {"industry": null, "quote": null} when the document does not make the \
            industry clear.""";

    private static final int MAX_COMPLETION_TOKENS = 300;
    private static final int MAX_TEXT_CHARS = 12_000;

    private IndustryScan() {}

    /**
     * Industries recorded by one scan.
     *
     * @param recorded number of industry records written
     * @param newIndustries how many of them the installed packs had not seen
     */
    public record Counts(int recorded, int newIndustries) {}

    /**
     * Scans every document version and records the mandate's industries.
     *
     * @param mandateId mandate scope
     * @param assessment repository bound to the caller's transaction
     * @param docs document repository for provenance writes
     * @param versions the mandate's document versions, document-then-version order
     * @param dbPath workspace database path (packs are published beside it)
     * @param client document LLM seam, or null for the offline path
     * @param usageSink receives every model call's usage
     * @return the recorded counts
     * @throws Exception if a read or write fails
     */
    public static Counts scan(long mandateId, AssessmentRepository assessment,
            DocumentRepository docs, List<VersionRow> versions, Path dbPath,
            DocLlmClient client, Consumer<DocLlmClient.Usage> usageSink) throws Exception {
        Map<String, DocTypePack> packs = candidatePacks(dbPath);
        Map<String, Pattern> known = knownIndustries(packs);
        Set<String> recorded = new HashSet<>();
        int newIndustries = 0;
        for (VersionRow version : versions) {
            List<PageText> pages = assessment.pageTexts(version.documentVersionId());
            if (pages.isEmpty()) {
                // No stored text: nothing to read from and nothing to ask about.
                continue;
            }
            boolean confirmed = false;
            for (Map.Entry<String, Pattern> industry : known.entrySet()) {
                Optional<Hit> hit = findToken(pages, industry.getValue());
                if (hit.isEmpty()) {
                    continue;
                }
                confirmed = true;
                if (!recorded.add(industry.getKey())) {
                    continue;
                }
                record(mandateId, assessment, docs, version, industry.getKey(),
                        industry.getKey(), false, "mentioned in the document text", hit.get());
            }
            if (confirmed || client == null || hasSpecificTags(packs, version)) {
                continue;
            }
            Optional<LlmIndustry> answer = ask(client, pages, usageSink);
            if (answer.isEmpty()) {
                continue;
            }
            String key = normalize(answer.get().industry());
            if (key.isEmpty() || !recorded.add(key)) {
                continue;
            }
            boolean isNew = !known.containsKey(key);
            if (isNew) {
                newIndustries++;
            }
            record(mandateId, assessment, docs, version,
                    isNew ? answer.get().industry().trim() : key, key, isNew,
                    "read by the AI from the document text",
                    anchor(pages, answer.get().quote()));
        }
        return new Counts(recorded.size(), newIndustries);
    }

    /** Seed packs plus every pack published beside the workspace; later layers win. */
    private static Map<String, DocTypePack> candidatePacks(Path dbPath) throws Exception {
        Map<String, DocTypePack> byCode = new LinkedHashMap<>();
        for (DocTypePack pack : PackLoader.loadSeedPacks()) {
            byCode.put(pack.code(), pack);
        }
        Path packsRoot = dbPath.toAbsolutePath().getParent() == null
                ? Path.of("packs")
                : dbPath.toAbsolutePath().getParent().resolve("packs");
        try {
            for (DocTypePack pack : new PackStore(packsRoot).loadAll()) {
                byCode.put(pack.code(), pack);
            }
        } catch (RuntimeException e) {
            // A broken induced-pack registry must not block the assessment.
            System.err.println("[doc-assess] ignoring pack store " + packsRoot + ": "
                    + e.getMessage());
        }
        return byCode;
    }

    /**
     * The industries the installed packs have seen: normalised key to the whole-word
     * pattern of the industry's leading term, first seed order preserved.
     */
    private static Map<String, Pattern> knownIndustries(Map<String, DocTypePack> packs) {
        Map<String, Pattern> known = new LinkedHashMap<>();
        for (DocTypePack pack : packs.values()) {
            for (String tag : pack.industryTags()) {
                String key = normalize(tag);
                if (key.isEmpty() || MARKERS.contains(key)) {
                    continue;
                }
                known.computeIfAbsent(key, k -> Pattern.compile(
                        "(?i)\\b" + Pattern.quote(firstWord(k)) + "\\b"));
            }
        }
        return known;
    }

    /** True when the version's classified pack declares a specific industry tag. */
    private static boolean hasSpecificTags(Map<String, DocTypePack> packs, VersionRow version) {
        DocTypePack pack = version.docTypeCode() == null ? null
                : packs.get(version.docTypeCode());
        if (pack == null) {
            return false;
        }
        for (String tag : pack.industryTags()) {
            String key = normalize(tag);
            if (!key.isEmpty() && !MARKERS.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** The first line naming this industry's leading term, in page order. */
    private static Optional<Hit> findToken(List<PageText> pages, Pattern token) {
        for (PageText page : pages) {
            for (String line : page.text().split("\\R")) {
                if (token.matcher(line).find()) {
                    return Optional.of(new Hit(
                            AssessmentRepository.normalizeWhitespace(line), page.pageIndex()));
                }
            }
        }
        return Optional.empty();
    }

    /** Anchors a model quote to the line it came from; unanchorable quotes stay page-less. */
    private static Hit anchor(List<PageText> pages, String quote) {
        String needle = AssessmentRepository.normalizeWhitespace(quote);
        if (needle == null || needle.isBlank()) {
            return Hit.NONE;
        }
        for (PageText page : pages) {
            for (String line : page.text().split("\\R")) {
                String normalized = AssessmentRepository.normalizeWhitespace(line);
                if (normalized.contains(needle)) {
                    return new Hit(normalized, page.pageIndex());
                }
            }
        }
        return new Hit(needle, null);
    }

    /** Asks the model to name the document's industry; the reply is never trusted blindly. */
    private static Optional<LlmIndustry> ask(DocLlmClient client, List<PageText> pages,
            Consumer<DocLlmClient.Usage> usageSink) {
        String text = joinPages(pages);
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            DocLlmClient.Completion completion = client.complete(SYSTEM,
                    "Document text:\n" + text, MAX_COMPLETION_TOKENS);
            usageSink.accept(completion.usage());
            return interpret(completion.content());
        } catch (DocLlmException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] industry LLM failed: " + e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] industry LLM failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Interprets the model reply; tolerant of prose around the JSON object. */
    static Optional<LlmIndustry> interpret(String content) {
        JsonNode node;
        try {
            node = DocLlmJson.object(content);
        } catch (DocLlmException e) {
            return Optional.empty();
        }
        if (!node.hasNonNull("industry")) {
            return Optional.empty();
        }
        String industry = node.get("industry").asText().trim();
        if (industry.isEmpty()) {
            return Optional.empty();
        }
        String quote = node.hasNonNull("quote") ? node.get("quote").asText().trim() : "";
        return Optional.of(new LlmIndustry(industry, quote));
    }

    /** Writes the record and, when the line anchors, its page provenance. */
    private static void record(long mandateId, AssessmentRepository assessment,
            DocumentRepository docs, VersionRow version, String industry, String key,
            boolean isNew, String reason, Hit hit) throws Exception {
        long recordId = assessment.insertIndustryRecord(mandateId, industry, key, isNew,
                reason, version.documentVersionId());
        if (hit.line() == null) {
            return;
        }
        Optional<BlockAnchor> anchor = assessment.findBlockAnchor(
                version.documentVersionId(), hit.line());
        if (anchor.isEmpty()) {
            return;
        }
        BlockAnchor block = anchor.get();
        long provenanceId = docs.insertProvenance(AssessmentRepository.INDUSTRY_ENTITY_TYPE,
                recordId, version.documentVersionId(), block.pageId(), block.blockId(),
                null, null, block.bboxX0(), block.bboxY0(), block.bboxX1(), block.bboxY1(),
                null, null, hit.line(), null);
        assessment.setIndustryProvenance(recordId, provenanceId);
    }

    private static String joinPages(List<PageText> pages) {
        StringBuilder sb = new StringBuilder();
        for (PageText page : pages) {
            if (sb.length() >= MAX_TEXT_CHARS) {
                break;
            }
            sb.append("[page ").append(page.pageIndex()).append("]\n")
                    .append(page.text()).append('\n');
        }
        return sb.length() <= MAX_TEXT_CHARS ? sb.toString() : sb.substring(0, MAX_TEXT_CHARS);
    }

    /** Normalised industry key: lowercase words, e.g. {@code "solar_power"} → {@code "solar power"}. */
    static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String firstWord(String key) {
        int space = key.indexOf(' ');
        return space < 0 ? key : key.substring(0, space);
    }

    /** One text hit: the line and the page it was found on. */
    private record Hit(String line, Integer pageIndex) {
        static final Hit NONE = new Hit(null, null);
    }

    /** The model's industry and the quote it was read from. */
    record LlmIndustry(String industry, String quote) {}
}
