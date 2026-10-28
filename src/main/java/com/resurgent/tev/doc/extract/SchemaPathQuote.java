package com.resurgent.tev.doc.extract;

/**
 * Distinguishes JSON-schema paths from layout words in extract citations.
 * Detection is structural (dotted identifier and index segments) so every
 * document type using LlamaExtract is covered without field-name switches.
 */
final class SchemaPathQuote {

    private SchemaPathQuote() {}

    /**
     * Layout-like {@code matching_text} when present, else extracted value text,
     * else {@code null} so the caller omits the citation.
     */
    static String quoteOrFallback(String matchingText, String valueText) {
        if (usableLayoutQuote(matchingText)) {
            return matchingText;
        }
        if (valueText != null && !valueText.isBlank()) {
            return valueText;
        }
        return null;
    }

    /**
     * True when {@code text} is a dotted JSON-schema path (identifier segments
     * and numeric array indexes) rather than words from a page.
     */
    static boolean looksLikeJsonSchemaPath(String text) {
        if (text == null || text.isBlank() || containsWhitespace(text)) {
            return false;
        }
        String[] segments = text.split("\\.", -1);
        if (segments.length < 2) {
            return false;
        }
        boolean sawIdentifier = false;
        boolean sawIndex = false;
        boolean sawSnake = false;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return false;
            }
            if (isIndex(segment)) {
                sawIndex = true;
                continue;
            }
            if (!isSchemaToken(segment)) {
                return false;
            }
            sawIdentifier = true;
            if (segment.indexOf('_') >= 0) {
                sawSnake = true;
            }
        }
        if (!sawIdentifier) {
            return false;
        }
        if (isNumericPageText(segments)) {
            return false;
        }
        return sawIndex || sawSnake || segments.length >= 3;
    }

    /**
     * Page figures and section numbers such as {@code Rs.4.50}, {@code Sec.3.2},
     * and {@code v1.2.3}: a short or version-like token followed only by numeric
     * tails. Indexed schema paths such as {@code assets.0} keep a longer field
     * name, so they stay paths.
     */
    private static boolean isNumericPageText(String[] segments) {
        if (segments.length < 2) {
            return false;
        }
        for (int i = 1; i < segments.length; i++) {
            if (!isIndex(segments[i])) {
                return false;
            }
        }
        String first = segments[0];
        if (first.indexOf('_') >= 0) {
            return false;
        }
        if (containsDigit(first)) {
            return true;
        }
        return first.length() <= 3;
    }

    private static boolean containsDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    private static boolean usableLayoutQuote(String matchingText) {
        return matchingText != null
                && !matchingText.isBlank()
                && !looksLikeJsonSchemaPath(matchingText);
    }

    private static boolean containsWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIndex(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * A schema leaf token: starts with a letter, then letters, digits, or
     * underscores. Shape only — not a catalogue of field names.
     */
    private static boolean isSchemaToken(String segment) {
        char first = segment.charAt(0);
        if (!isLetter(first)) {
            return false;
        }
        for (int i = 1; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (!isLetter(c) && (c < '0' || c > '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
