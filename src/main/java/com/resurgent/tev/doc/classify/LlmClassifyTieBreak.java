package com.resurgent.tev.doc.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Live LLM tie-break for document classification (S2.12 step 4). Called only when
 * deterministic scoring is tied or below the floor.
 *
 * <p>The prompt carries the file role/name and the page-1 label/signal text (truncated),
 * never the full document. The model must choose one of the already-scored candidate
 * packs or decline; a code outside that set is treated as a decline, so a pack is never
 * invented. Any failure declines and leaves the document {@code unknown}. Usage is
 * reported to the injected sink for {@code llm_usage}.
 */
public final class LlmClassifyTieBreak implements ClassifyTieBreak {

    /** Page-1 text is labels/signals; cap it so the tie-break stays cheap. */
    static final int MAX_PAGE_CHARS = 2_500;

    /** Candidate list cap (defensive: the deterministic filter already narrows this). */
    static final int MAX_CANDIDATES = 12;

    private static final int MAX_COMPLETION_TOKENS = 2_000;

    private static final String SYSTEM = """
            You classify a business document into one of a small set of known document-type \
            packs. You are given the file role, the file name, the first-page labels/signals, \
            and the candidate packs with their deterministic scores.
            Choose the single pack that best fits the document's role and printed labels.
            If none of the candidates genuinely fits the document type, decline.
            Reply with JSON only, no prose:
            {"pack_code": "<one candidate code, or \\"none\\">", "confidence": <0.0-1.0>, "reason": "<short>"}
            Never invent a pack code that is not in the candidate list.""";

    private final DocLlmClient client;
    private final Consumer<DocLlmClient.Usage> usageSink;

    /**
     * @param client    live client (required)
     * @param usageSink receives this call's usage for {@code llm_usage} (may be null)
     */
    public LlmClassifyTieBreak(DocLlmClient client, Consumer<DocLlmClient.Usage> usageSink) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.usageSink = usageSink;
    }

    @Override
    public Optional<TieBreakChoice> resolve(TieBreakContext context) {
        if (context == null || context.candidates().isEmpty()) {
            return Optional.empty();
        }
        try {
            DocLlmClient.Completion completion =
                    client.complete(SYSTEM, userPrompt(context), MAX_COMPLETION_TOKENS);
            report(completion.usage());
            if (System.getenv("DOC_LLM_DEBUG") != null) {
                System.err.println("[doc-classify] raw reply: " + completion.content());
            }
            return interpret(completion.content(), context.candidates());
        } catch (DocLlmException e) {
            report(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-classify] tie-break LLM failed: " + e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            report(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-classify] tie-break failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static String userPrompt(TieBreakContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("File role: ").append(blankToNone(context.fileRole())).append('\n');
        sb.append("File name: ").append(blankToNone(context.fileName())).append('\n');
        if (context.industryTag() != null && !context.industryTag().isBlank()) {
            sb.append("Industry hint: ").append(context.industryTag().trim()).append('\n');
        }
        sb.append("\nCandidate packs (score = deterministic identity-signal score):\n");
        List<ScoredPack> candidates = context.candidates().size() > MAX_CANDIDATES
                ? context.candidates().subList(0, MAX_CANDIDATES)
                : context.candidates();
        for (ScoredPack candidate : candidates) {
            sb.append("- ").append(candidate.packCode());
            if (candidate.packVersion() != null && !candidate.packVersion().isBlank()) {
                sb.append('@').append(candidate.packVersion());
            }
            sb.append(" score=").append(String.format(Locale.ROOT, "%.2f", candidate.score()));
            if (!candidate.matchedSignals().isEmpty()) {
                sb.append(" matched=").append(String.join(", ", candidate.matchedSignals()));
            }
            sb.append('\n');
            if (!candidate.identitySignals().isEmpty()) {
                sb.append("    identifies: ")
                        .append(String.join(", ", cap(candidate.identitySignals(), 8)))
                        .append('\n');
            }
        }
        sb.append("\nPage 1 labels/signals:\n");
        String page = context.page1Text() == null ? "" : context.page1Text().trim();
        if (page.length() > MAX_PAGE_CHARS) {
            page = page.substring(0, MAX_PAGE_CHARS);
        }
        sb.append(page.isEmpty() ? "(no page-1 text)" : page).append('\n');
        return sb.toString();
    }

    /** Accepts only a candidate code; anything else (including "none") declines. */
    static Optional<TieBreakChoice> interpret(String content, List<ScoredPack> candidates) {
        JsonNode node;
        try {
            node = DocLlmJson.object(content);
        } catch (DocLlmException e) {
            return Optional.empty();
        }
        String code = node.path("pack_code").asText("").trim();
        if (code.contains("@")) {
            code = code.substring(0, code.indexOf('@')).trim();
        }
        if (code.isEmpty() || "none".equalsIgnoreCase(code) || "unknown".equalsIgnoreCase(code)) {
            return Optional.empty();
        }
        String matched = null;
        for (ScoredPack candidate : candidates) {
            if (candidate.packCode() != null && candidate.packCode().equalsIgnoreCase(code)) {
                matched = candidate.packCode();
                break;
            }
        }
        if (matched == null) {
            return Optional.empty();
        }
        double confidence = node.path("confidence").isNumber()
                ? clamp(node.get("confidence").asDouble(), 0.0, 1.0)
                : 0.5;
        String reason = node.path("reason").asText("").trim();
        if (reason.isEmpty()) {
            reason = "LLM chose " + matched;
        }
        return Optional.of(new TieBreakChoice(matched, confidence, reason));
    }

    private void report(DocLlmClient.Usage usage) {
        if (usageSink != null && usage != null) {
            usageSink.accept(usage);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static List<String> cap(List<String> values, int max) {
        return values.size() <= max ? values : values.subList(0, max);
    }

    private static String blankToNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value.trim();
    }
}
