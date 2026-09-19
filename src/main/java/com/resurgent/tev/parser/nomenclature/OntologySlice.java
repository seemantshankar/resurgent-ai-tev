package com.resurgent.tev.parser.nomenclature;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The vocabulary fragment sent with a Packet: global spine plus the mandate's
 * industry pack and overlay.
 */
public record OntologySlice(
        IndustryResolution industry,
        List<NomenclatureNode> nodes,
        List<NomenclatureAlias> aliases,
        List<ProjectFactField> projectFactFields) {

    /** Path separator in a nomenclature path and in a qualified FM label. */
    public static final String SEPARATOR = " > ";

    public OntologySlice(
            IndustryResolution industry,
            List<NomenclatureNode> nodes,
            List<NomenclatureAlias> aliases) {
        this(industry, nodes, aliases, List.of());
    }

    public OntologySlice {
        projectFactFields = projectFactFields == null ? List.of() : List.copyOf(projectFactFields);
    }

    /**
     * What a verbatim label resolved to. A label that names something living under
     * more than one parent is {@code AMBIGUOUS} and binds nothing: list order must
     * never decide which parent wins.
     */
    public record Resolution(Kind kind, List<String> paths) {

        public enum Kind { NONE, UNIQUE, AMBIGUOUS }

        public Resolution {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }

        static Resolution of(List<String> paths) {
            if (paths.isEmpty()) {
                return new Resolution(Kind.NONE, List.of());
            }
            if (paths.size() > 1) {
                return new Resolution(Kind.AMBIGUOUS, paths);
            }
            return new Resolution(Kind.UNIQUE, paths);
        }

        public Optional<String> unique() {
            return kind == Kind.UNIQUE ? Optional.of(paths.get(0)) : Optional.empty();
        }
    }

    public Optional<ProjectFactField> projectFactField(String path) {
        return projectFactFields.stream().filter(f -> f.path().equals(path)).findFirst();
    }

    /** Node lookup by path, compared normalised so spacing and case cannot lose a node. */
    public Optional<NomenclatureNode> node(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String needle = normalizePath(path);
        return nodes.stream().filter(n -> normalizePath(n.path()).equals(needle)).findFirst();
    }

    public Optional<String> leafPathForAlias(String aliasText) {
        return aliases.stream()
                .filter(a -> a.aliasText().equals(aliasText))
                .map(NomenclatureAlias::leafPath)
                .findFirst();
    }

    /**
     * Bind a verbatim FM label to a nomenclature path, or nothing when the label is
     * unknown or names more than one path. See {@link #resolveLabel}.
     */
    public Optional<String> resolve(String verbatim) {
        return resolveLabel(verbatim).unique();
    }

    /**
     * Resolve a verbatim FM label, collecting every match so a collision reports
     * ambiguous instead of the first row in list order.
     *
     * <p>A label carrying its own qualifier ({@code BUILDING > Less: Depreciation})
     * is matched segment-aware: its last segment names the node and its earlier
     * segments scope which branch that node may come from. A qualified label never
     * falls back to prefix matching, which would let {@code BUILDING > …} be stolen
     * by the {@code Building} leaf.
     */
    public Resolution resolveLabel(String verbatim) {
        if (verbatim == null || verbatim.isBlank()) {
            return Resolution.of(List.of());
        }
        List<String> segments = segmentsOf(verbatim);
        if (segments.size() > 1) {
            return Resolution.of(qualifiedMatches(segments));
        }
        String needle = normalize(verbatim);
        List<String> exact = exactMatches(needle);
        if (!exact.isEmpty()) {
            return Resolution.of(exact);
        }
        return Resolution.of(prefixMatches(needle));
    }

    /** Exact alias, node name, or full node path — all matches, aliases first. */
    private List<String> exactMatches(String needle) {
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        for (NomenclatureAlias alias : aliases) {
            if (normalize(alias.aliasText()).equals(needle)) {
                hits.add(alias.leafPath());
            }
        }
        if (!hits.isEmpty()) {
            return List.copyOf(hits);
        }
        for (NomenclatureNode node : nodes) {
            if (normalizePath(node.path()).equals(needle)
                    || normalize(node.name()).equals(needle)) {
                hits.add(node.path());
            }
        }
        return List.copyOf(hits);
    }

    /**
     * The longest delimited-prefix match, and every path sharing that length. Tokens
     * shorter than 3 characters never prefix-match, so {@code AC} alone does not
     * steal every line that merely contains those letters.
     */
    private List<String> prefixMatches(String needle) {
        LinkedHashSet<String> best = new LinkedHashSet<>();
        int bestLength = 0;
        for (NomenclatureAlias alias : aliases) {
            String token = normalize(alias.aliasText());
            if (!isDelimitedPrefix(needle, token)) {
                continue;
            }
            if (token.length() > bestLength) {
                best.clear();
                bestLength = token.length();
            }
            if (token.length() == bestLength) {
                best.add(alias.leafPath());
            }
        }
        for (NomenclatureNode node : nodes) {
            String token = normalize(node.name());
            if (!isDelimitedPrefix(needle, token)) {
                continue;
            }
            if (token.length() > bestLength) {
                best.clear();
                bestLength = token.length();
            }
            if (token.length() == bestLength) {
                best.add(node.path());
            }
        }
        return List.copyOf(best);
    }

    /**
     * Match a qualified label: the last segment names the node exactly, and every
     * qualifier that the catalog recognises must scope that node's branch. A
     * qualifier the catalog does not know carries no meaning and is ignored rather
     * than refusing the label.
     */
    private List<String> qualifiedMatches(List<String> segments) {
        String leafName = segments.get(segments.size() - 1);
        List<String> scopes = new ArrayList<>();
        for (String qualifier : segments.subList(0, segments.size() - 1)) {
            scopes.addAll(scopesOf(qualifier));
        }
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        for (NomenclatureAlias alias : aliases) {
            if (normalize(alias.aliasText()).equals(leafName)
                    && inScope(alias.leafPath(), scopes)) {
                hits.add(alias.leafPath());
            }
        }
        for (NomenclatureNode node : nodes) {
            if ((normalize(node.name()).equals(leafName)
                    || normalizePath(node.path()).equals(leafName))
                    && inScope(node.path(), scopes)) {
                hits.add(node.path());
            }
        }
        return List.copyOf(hits);
    }

    /**
     * The branch a qualifier names: a mid-level scopes itself; a leaf scopes its
     * parent, since a sibling line under the same parent is what such a qualifier
     * (an FM section heading like {@code BUILDING}) actually points at.
     */
    private List<String> scopesOf(String qualifier) {
        List<String> scopes = new ArrayList<>();
        List<String> matched = exactMatches(qualifier);
        if (matched.isEmpty()) {
            matched = prefixMatches(qualifier);
        }
        for (String path : matched) {
            Optional<NomenclatureNode> node = node(path);
            if (node.isPresent() && !node.get().leaf()) {
                scopes.add(normalizePath(path));
                continue;
            }
            List<String> parts = segmentsOf(path);
            if (parts.size() > 1) {
                scopes.add(String.join(SEPARATOR, parts.subList(0, parts.size() - 1)));
            }
        }
        return scopes;
    }

    private static boolean inScope(String path, List<String> scopes) {
        if (scopes.isEmpty()) {
            return true;
        }
        String normalized = normalizePath(path);
        for (String scope : scopes) {
            if (normalized.equals(scope) || normalized.startsWith(scope + SEPARATOR)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> segmentsOf(String text) {
        List<String> segments = new ArrayList<>();
        for (String part : text.split(">")) {
            String normalized = normalize(part);
            if (!normalized.isEmpty()) {
                segments.add(normalized);
            }
        }
        return segments;
    }

    /** Normalised whole path: segment spacing around {@code >} cannot change identity. */
    private static String normalizePath(String path) {
        return String.join(SEPARATOR, segmentsOf(path));
    }

    public static String normalize(String text) {
        return text.replace(' ', ' ').replaceAll("\\s+", " ").trim()
                .toLowerCase(Locale.ROOT);
    }

    static boolean isDelimitedPrefix(String haystack, String token) {
        if (token.length() < 3 || !haystack.startsWith(token)) {
            return false;
        }
        if (haystack.length() == token.length()) {
            return true;
        }
        char next = haystack.charAt(token.length());
        return next == ' ' || next == '(' || next == '/' || next == '-' || next == ':';
    }
}
