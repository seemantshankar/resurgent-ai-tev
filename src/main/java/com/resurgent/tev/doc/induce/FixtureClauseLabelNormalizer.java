package com.resurgent.tev.doc.induce;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic offline label normalisation (the M2 {@code OfflinePackMiningClient}
 * precedent): only labels that normalise to the same words group, so an offline run
 * can still promote repeated identical proposals but never guesses that two differently
 * worded labels mean one clause. Clause text and keys are never invented.
 */
public final class FixtureClauseLabelNormalizer implements ClauseLabelNormalizer {

    @Override
    public String id() {
        return "fixture-label-normalisation";
    }

    @Override
    public NormalizationResult normalize(NormalizationRequest request) {
        Map<String, List<ProposedLabel>> byKey = new LinkedHashMap<>();
        Map<String, String> keyOfFirstLabel = new LinkedHashMap<>();
        for (ProposedLabel label : request.labels()) {
            String key = keyOf(label.label());
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(label);
            keyOfFirstLabel.putIfAbsent(key, label.label());
        }
        List<LabelGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<ProposedLabel>> entry : byKey.entrySet()) {
            List<ProposedLabel> members = entry.getValue();
            String parentFamily = members.stream()
                    .map(ProposedLabel::parentFamily)
                    .filter(f -> f != null && !f.isBlank())
                    .findFirst()
                    .orElse(null);
            List<String> aliases = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (ProposedLabel member : members) {
                codes.add(member.code());
                if (!aliases.contains(member.label())) {
                    aliases.add(member.label());
                }
            }
            groups.add(new LabelGroup(
                    entry.getKey(), keyOfFirstLabel.get(entry.getKey()), parentFamily,
                    aliases, codes));
        }
        return new NormalizationResult(groups);
    }

    /** Lowercase words of the label — case and punctuation never split or merge a group. */
    static String keyOf(String label) {
        String key = label == null ? "" : label.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return key.isEmpty() ? "clause_type" : key;
    }
}
