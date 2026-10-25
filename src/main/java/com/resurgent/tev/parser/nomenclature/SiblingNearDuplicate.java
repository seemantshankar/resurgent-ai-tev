package com.resurgent.tev.parser.nomenclature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a proposed soft leaf restates a sibling the ontology already
 * carries (issue #158). Same-round Excel regions and one document batch each see
 * a frozen catalog, so two of them can name {@code Freight & Forwarding} and
 * {@code Freight and Forwarding}. The second binds to the first.
 *
 * <p>The comparison is only among immediate children of one parent. {@code &} and
 * {@code and} drop out, punctuation becomes a space, a trailing plural drops, and
 * word order does not matter. A shorter name is not a duplicate of a longer one:
 * {@code Modules} is not {@code Solar PV Modules}. A leaf the catalogue already
 * aliases to a child of the same parent uses that child; an alias of another head
 * is not.
 */
public final class SiblingNearDuplicate {

    /** {@code &} is already punctuation; only the word {@code and} joins the same way. */
    private static final Set<String> STOP = Set.of("and");

    private SiblingNearDuplicate() {}

    /**
     * The same path with punctuation outside a category name turned into spaces,
     * so a trailing period or a comma cannot become a second leaf.
     */
    public static String shaped(String proposedPath) {
        Head head = head(proposedPath);
        if (head == null) {
            return proposedPath;
        }
        String cleaned = head.leaf().replaceAll("[^A-Za-z0-9 /&'\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty() || cleaned.equals(head.leaf())) {
            return proposedPath;
        }
        return head.parent() + OntologySlice.SEPARATOR + cleaned;
    }

    /**
     * The existing path to bind instead of minting {@code proposedPath}, or null
     * when the proposal is a distinct leaf (including when it is already present).
     *
     * @param proposedPath full head chain, {@code parent > leaf}
     * @param existingPaths paths the living ontology already carries, earliest first
     */
    public static String prefer(String proposedPath, List<String> existingPaths) {
        Head head = head(proposedPath);
        if (head == null || existingPaths == null || existingPaths.contains(proposedPath)) {
            return null;
        }
        String signature = signature(head.leaf());
        if (signature.isEmpty()) {
            return null;
        }
        String prefix = head.parent() + OntologySlice.SEPARATOR;
        for (String existing : existingPaths) {
            if (existing == null || !existing.startsWith(prefix)) {
                continue;
            }
            String sibling = existing.substring(prefix.length());
            if (sibling.isEmpty() || sibling.contains(OntologySlice.SEPARATOR)) {
                continue;
            }
            if (signature.equals(signature(sibling))) {
                return existing;
            }
        }
        return null;
    }

    /**
     * The path an alias of the proposed leaf already names, when that path is an
     * immediate child of the same parent. An alias of another head is left alone
     * — a quotation's {@code Project Cost > Tax} is not the P&amp;L tax expense.
     */
    public static String aliasedSibling(String proposedPath, Map<String, String> aliases) {
        Head head = head(proposedPath);
        if (head == null) {
            return null;
        }
        String hit = aliasUnderParent(head.parent(), head.leaf(), aliases);
        return proposedPath.equals(hit) ? null : hit;
    }

    private record Head(String parent, String leaf) {}

    private static Head head(String path) {
        if (path == null) {
            return null;
        }
        int sep = path.lastIndexOf(OntologySlice.SEPARATOR);
        if (sep <= 0) {
            return null;
        }
        return new Head(path.substring(0, sep), path.substring(sep + OntologySlice.SEPARATOR.length()));
    }

    private static String aliasUnderParent(String parent, String leaf, Map<String, String> aliases) {
        if (aliases == null || aliases.isEmpty() || leaf.isBlank()) {
            return null;
        }
        String hit = aliases.get(OntologyVocabulary.norm(leaf));
        if (hit == null || hit.isBlank()) {
            return null;
        }
        String prefix = parent + OntologySlice.SEPARATOR;
        if (hit.equals(parent) || !hit.startsWith(prefix)) {
            return null;
        }
        if (hit.substring(prefix.length()).contains(OntologySlice.SEPARATOR)) {
            return null;
        }
        return hit;
    }

    /** Sorted content words, so order, join-words, plurals and punctuation agree. */
    private static String signature(String leaf) {
        if (leaf == null || leaf.isBlank()) {
            return "";
        }
        String text = leaf.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        List<String> tokens = new ArrayList<>();
        for (String token : text.split(" ")) {
            if (token.isEmpty() || STOP.contains(token)) {
                continue;
            }
            String stemmed = stem(token);
            if (!stemmed.isEmpty() && !STOP.contains(stemmed)) {
                tokens.add(stemmed);
            }
        }
        Collections.sort(tokens);
        return String.join(" ", tokens);
    }

    private static String stem(String token) {
        if (token.length() <= 3) {
            return token;
        }
        if (token.endsWith("ies")) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.endsWith("es") && token.length() > 4) {
            String before = token.substring(0, token.length() - 2);
            if (before.endsWith("s") || before.endsWith("x") || before.endsWith("z")
                    || before.endsWith("ch") || before.endsWith("sh") || before.endsWith("o")) {
                return before;
            }
        }
        if (token.endsWith("s") && !token.endsWith("ss")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }
}
