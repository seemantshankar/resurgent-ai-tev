package com.resurgent.tev.doc.extract;

import java.util.Locale;

/**
 * Letterhead, stamp, and boilerplate lines that are not document facts (ADR 0036).
 * Applies to every pack: a Phone / Offices / Disclaimer line is page chrome on a
 * certificate, a quotation, or an agreement.
 */
public final class PageFurniture {

    private PageFurniture() {}

    /**
     * A derived extra field key that is letterhead or stamp, not a pack leaf.
     * {@code registered_office} stays; a footer {@code address} does not.
     */
    public static boolean fieldKey(String fieldKey) {
        String key = snake(fieldKey);
        if (key.length() < 3 || key.chars().noneMatch(Character::isLetter)) {
            return true;
        }
        if (key.equals("registered_office") || key.equals("place_of_issue")) {
            return false;
        }
        return key.equals("page") || key.startsWith("page_")
                || key.equals("phone") || key.startsWith("phone_")
                || key.equals("email") || key.startsWith("email")
                || key.equals("mobile") || key.startsWith("mobile")
                || key.equals("place") || key.equals("date") || key.equals("note")
                || key.equals("frn") || key.contains("_frn")
                || key.equals("our_offices_at") || key.equals("address")
                || key.endsWith("_address") || key.equals("partner")
                || key.equals("disclaimer") || key.equals("jointly_hold")
                || key.equals("ifsc") || key.endsWith("_ifsc")
                || key.equals("account_no") || key.endsWith("_account_no")
                || key.equals("bank") || key.endsWith("_bank")
                || key.equals("branch") || key.endsWith("_branch");
    }

    /** A printed label that names only letterhead / stamp chrome. */
    static boolean label(String left) {
        if (left == null || left.isBlank()) {
            return false;
        }
        String key = LabeledLineFieldKeys.fromLabel(left);
        return key != null && fieldKey(key);
    }

    /**
     * A whole line that is letterhead or notes chrome. Date / Place lines stay
     * readable — they are often the document date — and are only refused as
     * extra keys via {@link #fieldKey(String)}.
     */
    static boolean line(String text) {
        String folded = fold(text);
        if (folded.isEmpty()) {
            return false;
        }
        return folded.equals("page") || folded.startsWith("page ")
                || folded.startsWith("phone") || folded.startsWith("email")
                || folded.contains("offices at") || folded.startsWith("our offices")
                || folded.startsWith("frn") || folded.startsWith("partner")
                || folded.startsWith("notes to") || folded.startsWith("disclaimer")
                || folded.contains("only evidences")
                || folded.startsWith("©") || folded.startsWith("address :")
                || folded.startsWith("address:");
    }

    private static String snake(String fieldKey) {
        if (fieldKey == null) {
            return "";
        }
        return fieldKey.toLowerCase(Locale.ROOT).replace('©', ' ')
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    /**
     * A label a person would print on a form. OCR snow and punctuation runs
     * are not extras to publish on any document type.
     */
    public static boolean printableLabel(String left) {
        if (left == null) {
            return false;
        }
        String text = left.trim();
        if (text.length() < 3 || text.length() > 60 || ocrSnow(text)) {
            return false;
        }
        int letters = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
            } else if (!Character.isWhitespace(c) && c != '/' && c != '-' && c != '&'
                    && c != '.' && c != '(' && c != ')' && c != ',') {
                other++;
            }
        }
        return letters >= 2 && other <= letters
                && (hasRealWord(text) || isAcronym(text) || twoWordForm(text));
    }

    /** An extra key derived from garbage or chrome, not a real leaf. */
    public static boolean implausibleExtraKey(String fieldKey) {
        if (fieldKey(fieldKey)) {
            return true;
        }
        String key = snake(fieldKey);
        if (key.length() < 3 || key.length() > 48) {
            return true;
        }
        long letters = key.chars().filter(Character::isLetter).count();
        long seps = key.chars().filter(c -> c == '_').count();
        if (letters < 3 || seps >= 8) {
            return true;
        }
        return !hasRealWord(key.replace('_', ' ')) && !isAcronym(key)
                && !key.matches("[a-z]{2,8}_[a-z]{2,8}");
    }

    /**
     * An extra (not a pack field) that is safe to publish on any document type.
     * Pack fields skip this — they are named by the pack, not inferred.
     */
    public static boolean publishableExtra(String fieldKey, String label, String value) {
        return fieldKey != null
                && !implausibleExtraKey(fieldKey)
                && printableLabel(label)
                && printableValue(value)
                && !specDump(value);
    }

    /**
     * Value text that is not OCR snow. Amounts, dates, identifiers, names, and
     * short form answers pass. Equals-sign runs and letter salad do not.
     */
    public static boolean printableValue(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        if (text.isEmpty() || text.length() > 200 || ocrSnow(text) || specDump(text)) {
            return false;
        }
        if (looksNumeric(text) || looksIdentity(text)) {
            return true;
        }
        return hasRealWord(text) && looksNamed(text);
    }

    /** Table-spec leftover: a long slash-separated bill-of-quantities cell. */
    public static boolean specDump(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        int slashes = 0;
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '/') {
                slashes++;
            }
            if (Character.isLetterOrDigit(c)) {
                if (!inWord) {
                    words++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return slashes >= 2 && words >= 6;
    }

    static boolean ocrSnow(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        if (twoWordForm(text) || isAcronym(text)) {
            return false;
        }
        int letters = 0;
        int digits = 0;
        int marks = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
            } else if (Character.isDigit(c)) {
                digits++;
            } else if (c == '=' || c == '>' || c == '<' || c == '_' || c == '*'
                    || c == '—' || c == '°' || c == '~' || c == '|' || c == '§'
                    || c == '¢' || c == '•' || c == '&') {
                marks++;
            }
        }
        if (marks >= 3 || (marks >= 1 && letters < 12)) {
            return true;
        }
        String[] tokens = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        int shortTokens = 0;
        int real = 0;
        for (String token : tokens) {
            if (token.isEmpty() || token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            if (token.length() <= 2) {
                shortTokens++;
            }
            if (hasRealWord(token)) {
                real++;
            }
        }
        return shortTokens >= 4
                || (shortTokens >= 3 && real == 0 && digits == 0)
                || (real == 0 && digits == 0 && letters >= 6);
    }

    private static boolean hasRealWord(String text) {
        if (text == null) {
            return false;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= 4 && token.matches(".*[aeiou].*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksNumeric(String text) {
        int digits = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                digits++;
            } else if (Character.isLetter(c)) {
                letters++;
            }
        }
        return digits >= 2 && digits >= letters;
    }

    private static boolean looksIdentity(String text) {
        String compact = text.replaceAll("[\\s-]", "");
        return compact.matches("(?i)[A-Z]{5}\\d{4}[A-Z]")
                || compact.matches("(?i)[A-Z]{4}\\d{5}[A-Z]")
                || compact.matches("(?i)[A-Z]\\d{5}[A-Z]{2}\\d{4}[A-Z]{3}\\d{6}")
                || compact.matches("(?i)\\d{2}[A-Z]{5}\\d{4}[A-Z][A-Z0-9]Z[A-Z0-9]")
                || compact.matches("(?i)\\d{8}[A-Z]{4,}\\d{0,6}");
    }

    /** PAN / TAN / CIN / GST and other 2–6 letter form labels. */
    private static boolean isAcronym(String text) {
        if (text == null) {
            return false;
        }
        String compact = text.trim();
        return compact.matches("(?i)[a-z]{2,6}") && compact.matches("(?i).*[aeiou].*");
    }

    /** Short form pairs: as on, as of, pin code, offer no. */
    private static boolean twoWordForm(String text) {
        return text != null && text.trim().toLowerCase(Locale.ROOT)
                .matches("[a-z]{2,8}[\\s/_-]+[a-z]{2,8}");
    }

    /** A name, place, or short titled phrase — not lowercase letter salad. */
    private static boolean looksNamed(String text) {
        int titled = 0;
        int words = 0;
        for (String token : text.split("[^A-Za-z]+")) {
            if (token.length() < 2) {
                continue;
            }
            words++;
            if (Character.isUpperCase(token.charAt(0))) {
                titled++;
            }
        }
        return titled >= 1 && titled * 2 >= words;
    }

    private static String fold(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9:]+", " ").trim();
    }
}
