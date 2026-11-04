package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.induce.EntityTokenFilter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns unread sentences into stated-claim facts when a cloud call is allowed
 * (ADR 0036, #208). The verbatim quote is always a whole sentence from the page.
 */
final class StatedClaimReader {

    /**
     * Schema-open cloud extract. {@code fact.extraction_method} already allows
     * {@code llm_open} (beside {@code llm_field}); stated claims are not pack-schema
     * fills, so they use that token rather than a new CHECK value.
     */
    static final String METHOD = "llm_open";

    private static final Pattern SENTENCE = Pattern.compile("[^.!?]+[.!?]?");

    private static final Set<String> EVALUATIVE = Set.of(
            "large", "small", "huge", "significant", "substantial", "major");

    private static final Set<String> ENTITY_KEYS = Set.of(
            "company", "party", "entity", "company_name", "party_name", "subject");

    private StatedClaimReader() {}

    /**
     * Asks the cloud client to name claims in unread sentences and merges those
     * that are not already stored as labeled-line facts.
     */
    static ExtractResult apply(ExtractClient client, ExtractRequest request,
            ExtractResult local, List<String> unread, Set<String> reserved) {
        List<String> sentences = unreadSentences(unread, reserved);
        if (sentences.isEmpty()) {
            return local;
        }
        Map<String, String> options = new LinkedHashMap<>(request.options());
        options.put(ExtractRequest.STATED_CLAIM_SENTENCES, String.join("\n", sentences));
        ExtractRequest claimRequest = new ExtractRequest(
                request.pageBundleDir(), request.originalFile(), request.pageIndices(),
                request.langHint(), request.docTypeCode(), request.instructions(), options);
        ExtractResult cloud;
        try {
            cloud = client.extractStatedClaims(claimRequest);
        } catch (ExtractException e) {
            return local;
        }
        List<FactCandidate> claims = fromPayload(cloud.payload(), sentences, local.facts());
        if (claims.isEmpty()) {
            return local;
        }
        List<FactCandidate> facts = new ArrayList<>(local.facts());
        List<Citation> citations = new ArrayList<>(local.citations());
        facts.addAll(claims);
        for (FactCandidate claim : claims) {
            citations.addAll(claim.citations());
        }
        return new ExtractResult(
                cloud.extractorId() != null ? cloud.extractorId() : local.extractorId(),
                cloud.payload() != null ? cloud.payload() : local.payload(),
                facts, citations,
                cloud.stats() != null ? cloud.stats() : local.stats());
    }

    static List<String> unreadSentences(List<String> unread, Set<String> reserved) {
        List<String> sentences = new ArrayList<>();
        Set<String> reservedFolded = new HashSet<>();
        if (reserved != null) {
            for (String text : reserved) {
                if (text != null && !text.isBlank()) {
                    reservedFolded.add(fold(text));
                }
            }
        }
        if (unread == null) {
            return sentences;
        }
        for (String block : unread) {
            for (String sentence : splitSentences(block)) {
                if (covered(sentence, reservedFolded)) {
                    continue;
                }
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    static List<FactCandidate> fromPayload(JsonNode payload, List<String> sentences,
            List<FactCandidate> existing) {
        List<FactCandidate> claims = new ArrayList<>();
        if (payload == null || payload.isNull()) {
            return claims;
        }
        JsonNode array = payload.path("claims");
        if (!array.isArray()) {
            return claims;
        }
        Set<String> takenKeys = new HashSet<>();
        Set<String> takenValues = new HashSet<>();
        if (existing != null) {
            for (FactCandidate fact : existing) {
                if (fact.key() != null) {
                    takenKeys.add(fact.key().toLowerCase(Locale.ROOT));
                }
                if (fact.valueText() != null && !fact.valueText().isBlank()) {
                    takenValues.add(fold(fact.valueText()));
                }
            }
        }
        EntityTokenFilter entities = EntityTokenFilter.none();
        for (JsonNode node : array) {
            String rawKey = text(node, "key");
            String value = text(node, "value");
            if (rawKey.isBlank() || value.isBlank()) {
                continue;
            }
            String key = snake(rawKey);
            if (key.isBlank() || EVALUATIVE.contains(key) || ENTITY_KEYS.contains(key)) {
                continue;
            }
            if (EVALUATIVE.contains(value.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (entities.isEntityToken(rawKey) || entities.isEntityToken(value)
                    || entities.isEntityToken(key.replace('_', ' '))) {
                continue;
            }
            String keyFold = key.toLowerCase(Locale.ROOT);
            if (takenKeys.contains(keyFold) || takenValues.contains(fold(value))) {
                continue;
            }
            String quote = sentenceFor(text(node, "quote"), value, sentences);
            if (quote == null) {
                continue;
            }
            ValueKind kind = kindOf(text(node, "kind"));
            FactCandidate candidate = new FactCandidate(key, kind)
                    .valueText(value)
                    .extractionMethod(METHOD)
                    .statedOrDerived("stated")
                    .confidence(0.8);
            if (kind == ValueKind.DATE) {
                candidate.valueDate(value);
            }
            if (kind == ValueKind.QUANTITY) {
                try {
                    Matcher number = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(value);
                    if (number.find()) {
                        candidate.valueNumber(Double.parseDouble(number.group()));
                    }
                } catch (NumberFormatException ignored) {
                    // keep the stated text
                }
                String unit = value.replaceAll("^[\\d.,\\s]+", "").trim();
                if (!unit.isBlank()) {
                    candidate.unit(unit);
                }
            }
            Citation citation = Citation.of(1, quote);
            candidate.addCitation(citation);
            claims.add(candidate);
            takenKeys.add(keyFold);
            takenValues.add(fold(value));
        }
        return claims;
    }

    private static String sentenceFor(String quoted, String value, List<String> sentences) {
        if (quoted != null && !quoted.isBlank()) {
            String want = quoted.trim();
            for (String sentence : sentences) {
                if (sentence.equals(want)) {
                    return sentence;
                }
            }
        }
        String needle = value == null ? "" : value.trim();
        if (needle.isEmpty()) {
            return null;
        }
        String found = null;
        for (String sentence : sentences) {
            if (sentence.contains(needle)) {
                if (found != null) {
                    return found;
                }
                found = sentence;
            }
        }
        return found;
    }

    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sentences;
        }
        Matcher matcher = SENTENCE.matcher(text.trim());
        while (matcher.find()) {
            String sentence = matcher.group().trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty()) {
            sentences.add(text.trim());
        }
        return sentences;
    }

    private static boolean covered(String sentence, Set<String> reservedFolded) {
        String folded = fold(sentence);
        for (String reserved : reservedFolded) {
            if (reserved.contains(folded) || folded.contains(reserved) && reserved.length() > 8) {
                return true;
            }
        }
        return false;
    }

    private static String snake(String raw) {
        String fromLabel = LabeledLineFieldKeys.fromLabel(raw.replace('_', ' '));
        if (fromLabel != null && !fromLabel.isBlank()) {
            return fromLabel;
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private static ValueKind kindOf(String wire) {
        if (wire == null || wire.isBlank()) {
            return ValueKind.TEXT;
        }
        try {
            return ValueKind.fromWire(wire);
        } catch (IllegalArgumentException e) {
            return ValueKind.TEXT;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String fold(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
