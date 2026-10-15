package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * LLM assist for the site spellings whose loose score is ambiguous (issue #137, spec
 * #133): above {@link SiteScan#SITE_AMBIGUOUS_FLOOR} but below
 * {@link com.resurgent.tev.doc.compare.Comparisons#SITE_MATCH_SCORE}, so a deterministic
 * merge would be a guess. Asks whether the two spellings name the same site and records
 * the one-line reason on the alias row either way. Every call — success or failure — is
 * reported to the stage usage sink.
 */
public final class LlmSiteConfirmation {

    private static final String SYSTEM = """
            You decide whether two spellings of a project site name, met in the documents \
            of ONE mandate, name the same site.
            Reply with a single JSON object: {"same": true|false, "reason": "<one short line>"}.
            Transliteration variants, abbreviations and spacing differences of one name are \
            the same site; similar-sounding but different villages are not. When unsure, \
            reply {"same": false, "reason": "<why>"}.""";

    private static final int MAX_COMPLETION_TOKENS = 200;

    /** A model verdict: whether the spellings are one site, and why. */
    public record Verdict(boolean same, String reason) {}

    private LlmSiteConfirmation() {}

    /**
     * Asks the model whether two spellings name the same site.
     *
     * @param client document LLM seam ({@code null} keeps the offline path)
     * @param left one spelling (typically the site's form so far)
     * @param right the other spelling (typically the new mention)
     * @param score their loose score, for the model's context
     * @param usageSink receives every call's {@link DocLlmClient.Usage}
     * @return the verdict, or empty when the model did not answer (offline, failed or
     *         unparseable) — the caller then keeps the spellings apart
     */
    public static Optional<Verdict> ask(DocLlmClient client, String left, String right,
            double score, Consumer<DocLlmClient.Usage> usageSink) {
        if (client == null) {
            return Optional.empty();
        }
        try {
            DocLlmClient.Completion completion = client.complete(SYSTEM,
                    String.format("First spelling: %s%nSecond spelling: %s%nLoose score: %.2f",
                            left, right, score),
                    MAX_COMPLETION_TOKENS);
            usageSink.accept(completion.usage());
            return interpret(completion.content());
        } catch (DocLlmException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] site confirmation LLM failed: " + e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] site confirmation LLM failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Interprets the model reply; tolerant of prose around the JSON object. */
    static Optional<Verdict> interpret(String content) {
        JsonNode node;
        try {
            node = DocLlmJson.object(content);
        } catch (DocLlmException e) {
            return Optional.empty();
        }
        JsonNode sameNode = node.get("same");
        if (sameNode == null || !sameNode.isBoolean()) {
            return Optional.empty();
        }
        String reason = node.hasNonNull("reason") ? node.get("reason").asText().trim() : "";
        return Optional.of(new Verdict(sameNode.asBoolean(), reason));
    }
}
