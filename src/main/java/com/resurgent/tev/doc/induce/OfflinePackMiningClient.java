package com.resurgent.tev.doc.induce;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Production offline pack miner. Never touches the network. The no-arg miner
 * reads spine-matching labels from layout text so induction works without an
 * LLM key. A canned {@link MiningResult} is only for tests that already know
 * the schema.
 */
public final class OfflinePackMiningClient implements PackMiningClient {

    private final MiningResult result;
    private final String id;

    /** Heuristic offline miner: derives fields from layout/title labels. */
    public OfflinePackMiningClient() {
        this("offline-layout", null);
    }

    /**
     * @param result canned mining output (required)
     */
    public OfflinePackMiningClient(MiningResult result) {
        this("offline", result);
    }

    /**
     * @param id     adapter id (required)
     * @param result canned mining output, or {@code null} to use the heuristic
     */
    public OfflinePackMiningClient(String id, MiningResult result) {
        this.id = Objects.requireNonNull(id, "id");
        this.result = result;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public MiningResult mine(MiningRequest request) {
        if (result != null) {
            return result;
        }
        return heuristicMine(request);
    }

    /**
     * Deterministic label miner: every layout/title line that matches a spine leaf
     * becomes a candidate field; the line is recorded as appearing in that document.
     */
    static MiningResult heuristicMine(MiningRequest request) {
        List<MiningDocument> docs = request.documents() == null ? List.of() : request.documents();
        String fileRole = request.fileRole() == null || request.fileRole().isBlank()
                ? "document"
                : request.fileRole().trim().toLowerCase(Locale.ROOT);
        String family = fileRole.contains(".") ? fileRole.substring(0, fileRole.indexOf('.')) : fileRole;

        // label → (spine leaf, docIds, synonyms)
        record Acc(String key, String path, String pathRoot, String valueKind,
                   String label, Set<String> docIds, List<String> synonyms) {}
        java.util.Map<String, Acc> byKey = new java.util.LinkedHashMap<>();
        List<String> identitySignals = new ArrayList<>();

        for (MiningDocument d : docs) {
            List<String> lines = new ArrayList<>();
            if (d.title() != null && !d.title().isBlank()) {
                lines.add(d.title());
            }
            if (d.layoutText() != null) {
                lines.addAll(d.layoutText());
            }
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                String trimmed = line.trim();
                // candidate labels are short: "Tariff:", "Net Worth", "COD — 2027"
                String label = stripValue(trimmed);
                if (label.isEmpty() || label.length() > 60) {
                    continue;
                }
                var spine = SpineCatalog.matchLabel(label);
                if (spine.isEmpty()) {
                    // document-type titles become identity signals
                    if (isTitleLike(trimmed) && identitySignals.size() < 8) {
                        String signal = trimmed.length() > 60 ? trimmed.substring(0, 60) : trimmed;
                        if (!identitySignals.contains(signal)) {
                            identitySignals.add(signal);
                        }
                    }
                    continue;
                }
                var leaf = spine.get();
                String key = EntityTokenFilter.snake(leaf.leafName());
                Acc acc = byKey.get(key);
                if (acc == null) {
                    List<String> synonyms = new ArrayList<>();
                    synonyms.add(leaf.leafName());
                    if (!synonyms.contains(label)) {
                        synonyms.add(label);
                    }
                    byKey.put(key, new Acc(
                            key, leaf.path(), leaf.pathRoot(), guessKind(leaf),
                            leaf.leafName(),
                            new LinkedHashSet<>(List.of(d.id())),
                            synonyms));
                } else {
                    acc.docIds().add(d.id());
                    if (!acc.synonyms().contains(label) && acc.synonyms().size() < 6) {
                        acc.synonyms().add(label);
                    }
                }
            }
        }

        List<MinedField> fields = new ArrayList<>();
        for (Acc acc : byKey.values()) {
            fields.add(new MinedField(
                    acc.key(),
                    acc.path(),
                    acc.pathRoot(),
                    acc.valueKind(),
                    true,
                    acc.label(),
                    List.copyOf(acc.synonyms()),
                    List.copyOf(acc.synonyms()),
                    List.copyOf(acc.docIds()),
                    List.of()));
        }

        String code = request.docTypeHint() != null && !request.docTypeHint().isBlank()
                ? request.docTypeHint().trim()
                : family + ".induced";
        OfflineDomainCatalog.Supplement supplement = OfflineDomainCatalog.match(request);
        for (MinedField extra : supplement.fields()) {
            boolean present = fields.stream().anyMatch(field -> field.key().equals(extra.key()));
            if (!present) {
                fields.add(extra);
            }
        }
        String query = supplement.webSearchQuery();
        if (query == null || query.isBlank()) {
            query = StructuralFingerprint.webQuery(docs, request.industryTag());
        }
        return new MiningResult(
                code,
                family,
                identitySignals,
                fields,
                supplement.tables(),
                supplement.clauses(),
                supplement.passages(),
                supplement.documentDateKind(),
                query,
                supplement.domainRationale());
    }

    private static String stripValue(String line) {
        String s = line.trim();
        // drop trailing value after ':' or '=' or ' - ' when the left side is short
        for (String sep : new String[] {":", "=", " - ", " — ", "–"}) {
            int idx = s.indexOf(sep);
            if (idx > 2 && idx < 50) {
                String left = s.substring(0, idx).trim();
                if (!left.isEmpty() && left.length() <= 48) {
                    s = left;
                }
            }
        }
        // drop currency/amount tails
        s = s.replaceAll("(?i)\\b(rs\\.?|inr|₹|usd|\\$)\\s*[0-9].*$", "").trim();
        s = s.replaceAll("[0-9][0-9,]*(\\.[0-9]+)?\\s*(lakhs?|crores?|millions?|billions?)?\\s*$",
                "").trim();
        return s.replaceAll("[:=\\-–—]+$", "").trim();
    }

    private static boolean isTitleLike(String line) {
        String s = line.trim();
        if (s.length() < 6 || s.length() > 80) {
            return false;
        }
        // title-ish: mostly letters/spaces, a few words
        if (!s.matches("[A-Za-z0-9 &,.'()\\-/]+")) {
            return false;
        }
        String[] words = s.split("\\s+");
        return words.length >= 2 && words.length <= 8;
    }

    private static String guessKind(SpineCatalog.SpineLeaf leaf) {
        String name = Locale.ROOT == null ? leaf.leafName() : leaf.leafName().toLowerCase(Locale.ROOT);
        if (name.contains("date") || name.contains("as of") || name.contains("cod")
                || name.equals("cod") || name.contains("validity")) {
            return "date";
        }
        if (name.contains("net worth") || name.contains("assets") || name.contains("liabilities")
                || name.contains("cost") || name.contains("tariff") || name.contains("tax")
                || name.contains("freight") || name.contains("subtotal") || name.contains("gross")
                || name.contains("amount")) {
            return "money";
        }
        if (name.contains("capacity") || name.contains("tenor") || name.contains("term")
                || name.contains("area") || name.contains("yield") || name.contains("plf")) {
            return name.contains("tenor") || name.contains("term") ? "duration" : "quantity";
        }
        return "text";
    }
}
