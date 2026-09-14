package com.resurgent.tev.parser.nomenclature;

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

    public OntologySlice(
            IndustryResolution industry,
            List<NomenclatureNode> nodes,
            List<NomenclatureAlias> aliases) {
        this(industry, nodes, aliases, List.of());
    }

    public OntologySlice {
        projectFactFields = projectFactFields == null ? List.of() : List.copyOf(projectFactFields);
    }

    public Optional<ProjectFactField> projectFactField(String path) {
        return projectFactFields.stream().filter(f -> f.path().equals(path)).findFirst();
    }

    public Optional<NomenclatureNode> node(String path) {
        return nodes.stream().filter(n -> n.path().equals(path)).findFirst();
    }

    public Optional<String> leafPathForAlias(String aliasText) {
        return aliases.stream()
                .filter(a -> a.aliasText().equals(aliasText))
                .map(NomenclatureAlias::leafPath)
                .findFirst();
    }

    /**
     * Bind a verbatim FM label to a nomenclature path: exact alias or node name,
     * else the longest name/alias that is a delimited prefix of the label.
     * Tokens shorter than 3 characters never prefix-match (so {@code AC} alone
     * does not steal every line that merely contains those letters).
     */
    public Optional<String> resolve(String verbatim) {
        if (verbatim == null || verbatim.isBlank()) {
            return Optional.empty();
        }
        String needle = normalize(verbatim);
        for (NomenclatureAlias alias : aliases) {
            if (normalize(alias.aliasText()).equals(needle)) {
                return Optional.of(alias.leafPath());
            }
        }
        for (NomenclatureNode node : nodes) {
            if (normalize(node.name()).equals(needle) || normalize(node.path()).equals(needle)) {
                return Optional.of(node.path());
            }
        }
        String bestPath = null;
        int bestLength = 0;
        for (NomenclatureAlias alias : aliases) {
            String token = normalize(alias.aliasText());
            if (token.length() > bestLength && isDelimitedPrefix(needle, token)) {
                bestPath = alias.leafPath();
                bestLength = token.length();
            }
        }
        for (NomenclatureNode node : nodes) {
            String token = normalize(node.name());
            if (token.length() > bestLength && isDelimitedPrefix(needle, token)) {
                bestPath = node.path();
                bestLength = token.length();
            }
        }
        return Optional.ofNullable(bestPath);
    }

    public static String normalize(String text) {
        return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim()
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
