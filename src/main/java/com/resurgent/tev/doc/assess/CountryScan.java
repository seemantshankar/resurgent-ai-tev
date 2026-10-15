package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.BlockAnchor;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.db.AssessmentRepository.VersionRow;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code assess_profile} country step (M3-05, issue #138): records the mandate's
 * country — read from the registered address, the regulators named, the currency or
 * the place of signing — with its reason and page. One country per mandate.
 *
 * <p>Deterministic first: a document that names the country in its text is accepted
 * without a model call. Otherwise, a document carrying a country cue (the rupee
 * currency, crore/lakh scale, or an Indian regulator or tax identifier) may be read
 * by the model, until one document names the country. Offline runs record none
 * rather than guess.
 */
public final class CountryScan {

    /**
     * The country token recognised deterministically: the platform's home-country
     * evidence. Everything else is left to the model (or to a later ticket's country
     * vocabulary), so no country is ever guessed offline.
     */
    private static final Pattern COUNTRY_TOKEN = Pattern.compile("(?i)\\bindia(?:n)?\\b");

    /** Cues that a document may name a country without naming it directly. */
    private static final List<Pattern> CUES = List.of(
            Pattern.compile("₹"),
            Pattern.compile("(?i)\\brs\\."),
            Pattern.compile("(?i)\\binr\\b"),
            Pattern.compile("(?i)\\bcrores?\\b"),
            Pattern.compile("(?i)\\blakhs?\\b"),
            Pattern.compile("\\b(?:GST|GSTIN|PAN|TAN|SEBI|RBI|MCA|ROC)\\b"));

    private static final String SYSTEM = """
            You read the country of ONE project from one supporting document of a \
            project-finance mandate: the registered address, the regulators named, the \
            currency, or the place of signing.
            Reply with a single JSON object: {"country": "<country>", \
            "quote": "<verbatim text the country was read from>"}.
            Reply {"country": null, "quote": null} when the document does not make the \
            country clear. Never guess.""";

    private static final int MAX_COMPLETION_TOKENS = 300;
    private static final int MAX_TEXT_CHARS = 12_000;

    private CountryScan() {}

    /**
     * Country recorded by one scan.
     *
     * @param country the country name, or null when none was detected
     */
    public record Counts(String country) {}

    /**
     * Scans every document version and records the mandate's country, one per mandate.
     *
     * @param mandateId mandate scope
     * @param assessment repository bound to the caller's transaction
     * @param docs document repository for provenance writes
     * @param versions the mandate's document versions, document-then-version order
     * @param client document LLM seam, or null for the offline path
     * @param usageSink receives every model call's usage
     * @return the recorded country, or a count with null when none was found
     * @throws Exception if a read or write fails
     */
    public static Counts scan(long mandateId, AssessmentRepository assessment,
            DocumentRepository docs, List<VersionRow> versions, DocLlmClient client,
            Consumer<DocLlmClient.Usage> usageSink) throws Exception {
        for (VersionRow version : versions) {
            List<PageText> pages = assessment.pageTexts(version.documentVersionId());
            if (pages.isEmpty()) {
                // No stored text: nothing to read from and nothing to ask about.
                continue;
            }
            Hit hit = find(pages, COUNTRY_TOKEN).orElse(null);
            String country = "India";
            String reason = "named in the document text";
            if (hit == null) {
                if (client == null || !hasCue(pages)) {
                    continue;
                }
                Optional<LlmCountry> answer = ask(client, pages, usageSink);
                if (answer.isEmpty()) {
                    continue;
                }
                country = answer.get().country();
                reason = "read by the AI from the document text";
                hit = anchor(pages, answer.get().quote());
            }
            record(mandateId, assessment, docs, version, country, reason, hit);
            return new Counts(country);
        }
        return new Counts(null);
    }

    /** The first line naming the country, in page order. */
    private static Optional<Hit> find(List<PageText> pages, Pattern token) {
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

    /** True when any page carries a country cue. */
    private static boolean hasCue(List<PageText> pages) {
        for (PageText page : pages) {
            for (Pattern cue : CUES) {
                if (cue.matcher(page.text()).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Anchors a model quote to the line it came from; unanchorable quotes stay page-less. */
    private static Hit anchor(List<PageText> pages, String quote) {
        String needle = AssessmentRepository.normalizeWhitespace(quote);
        if (needle == null || needle.isBlank()) {
            return new Hit(null, null);
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

    /** Asks the model for the country; the reply is never trusted blindly. */
    private static Optional<LlmCountry> ask(DocLlmClient client, List<PageText> pages,
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
            System.err.println("[doc-assess] country LLM failed: " + e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] country LLM failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Interprets the model reply; tolerant of prose around the JSON object. */
    static Optional<LlmCountry> interpret(String content) {
        JsonNode node;
        try {
            node = DocLlmJson.object(content);
        } catch (DocLlmException e) {
            return Optional.empty();
        }
        if (!node.hasNonNull("country")) {
            return Optional.empty();
        }
        String country = node.get("country").asText().trim();
        if (country.isEmpty()) {
            return Optional.empty();
        }
        String quote = node.hasNonNull("quote") ? node.get("quote").asText().trim() : "";
        return Optional.of(new LlmCountry(country, quote));
    }

    /** Writes the record and, when the line anchors, its page provenance. */
    private static void record(long mandateId, AssessmentRepository assessment,
            DocumentRepository docs, VersionRow version, String country, String reason,
            Hit hit) throws Exception {
        long recordId = assessment.insertCountryRecord(mandateId, country,
                IndustryScan.normalize(country), reason, version.documentVersionId());
        if (hit.line() == null) {
            return;
        }
        Optional<BlockAnchor> anchor = assessment.findBlockAnchor(
                version.documentVersionId(), hit.line());
        if (anchor.isEmpty()) {
            return;
        }
        BlockAnchor block = anchor.get();
        long provenanceId = docs.insertProvenance(AssessmentRepository.COUNTRY_ENTITY_TYPE,
                recordId, version.documentVersionId(), block.pageId(), block.blockId(),
                null, null, block.bboxX0(), block.bboxY0(), block.bboxX1(), block.bboxY1(),
                null, null, hit.line(), null);
        assessment.setCountryProvenance(recordId, provenanceId);
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

    /** One text hit: the line and the page it was found on. */
    private record Hit(String line, Integer pageIndex) {}

    /** The model's country and the quote it was read from. */
    record LlmCountry(String country, String quote) {}
}
