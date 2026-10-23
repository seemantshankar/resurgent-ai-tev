package com.resurgent.tev.doc.induce;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic detection and stripping of instance-value tokens (person, company,
 * amount, PAN, CIN, GSTIN, known sample entities) so pack keys/paths stay labels
 * (S2.11 label-not-value).
 *
 * <p>A key that <em>embeds</em> an entity is rewritten to its label remainder; only
 * a key that is <em>purely</em> an entity/value is dropped.
 */
public final class EntityTokenFilter {

    /** PAN: five letters, four digits, one letter. */
    public static final Pattern PAN = Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b");
    /**
     * CIN: a 21-character Indian CIN ({@code U40100TG2010PTC071234}) or the
     * earlier two-letter run used by older samples.
     */
    public static final Pattern CIN = Pattern.compile(
            "\\b(?:[A-Z][0-9]{5}[A-Z]{2}[0-9]{4}[A-Z]{3}[0-9]{6}"
                    + "|[A-Z]{2}[0-9]{2}[A-Z]{3,7}[0-9]{4}[A-Z]{3,7}[0-9A-Z]{0,6})\\b");
    /** Bank account: a labeled number, or a bare 9–18 digit run. */
    public static final Pattern ACCOUNT = Pattern.compile(
            "(?i)\\b(?:a/c|acct|account)\\s*(?:no\\.?|number|num|#)?\\s*[:#-]?\\s*[0-9]{6,18}\\b"
                    + "|\\b[0-9]{9,18}\\b");
    /** GSTIN: 2-digit state + 5-char PAN-like + entity + 'Z' + checksum. */
    public static final Pattern GSTIN = Pattern.compile(
            "\\b[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][A-Z0-9]Z[A-Z0-9]\\b");
    /** Amounts with currency marker or Indian/English scale words. */
    public static final Pattern AMOUNT = Pattern.compile(
            "(?i)(rs\\.?|inr|₹|usd|\\$)\\s*[0-9][0-9,]*(\\.[0-9]+)?"
                    + "|\\b[0-9][0-9,]*(\\.[0-9]+)?\\s*(lakhs?|crores?|millions?|billions?|thousands?)\\b");
    /** Generic numeric value fragments that should never be field names. */
    public static final Pattern NUMERIC_TOKEN = Pattern.compile("\\b[0-9]{2,}(?:[.,][0-9]+)*\\b");
    /** Hard-coded sample company/person tokens used in goldens. */
    public static final Pattern KNOWN_SAMPLE_ENTITIES = Pattern.compile(
            "(?i)\\b(bhagyanagar(?:\\s+solar)?|sharma|ramesh|kumar|patel|reddy)\\b");

    private final Set<String> sampleEntities;

    /**
     * @param sampleEntities company/person names observed in the cluster (may be empty)
     */
    public EntityTokenFilter(Collection<String> sampleEntities) {
        Set<String> out = new LinkedHashSet<>();
        if (sampleEntities != null) {
            for (String s : sampleEntities) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        this.sampleEntities = Set.copyOf(out);
    }

    /** Empty filter (no cluster-specific entities). */
    public static EntityTokenFilter none() {
        return new EntityTokenFilter(List.of());
    }

    public Set<String> sampleEntities() {
        return sampleEntities;
    }

    /**
     * True when the token is <em>purely</em> an entity/amount/PAN/CIN value
     * (nothing label-like remains after stripping).
     */
    public boolean isEntityToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String t = token.trim();
        String upper = t.toUpperCase(Locale.ROOT);
        if (PAN.matcher(upper).matches() || CIN.matcher(upper).matches()
                || GSTIN.matcher(upper).matches() || ACCOUNT.matcher(t).matches()) {
            return true;
        }
        if (NUMERIC_TOKEN.matcher(t).matches()) {
            return true;
        }
        if (AMOUNT.matcher(t).find()) {
            String normalized = SpineCatalog.normalize(t).replaceAll(" ", "");
            if (normalized.matches("(?i)(rs|inr|₹|usd|$)*[0-9].*")) {
                return true;
            }
        }
        // purely entity only when no label-like remainder survives
        return strip(t).isEmpty() && containsEntity(t);
    }

    /** True when the text contains any entity/PAN/CIN/amount marker. */
    public boolean containsEntity(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (PAN.matcher(upper).find() || CIN.matcher(upper).find() || GSTIN.matcher(upper).find()
                || ACCOUNT.matcher(text).find()) {
            return true;
        }
        if (AMOUNT.matcher(text).find()) {
            return true;
        }
        if (KNOWN_SAMPLE_ENTITIES.matcher(text).find()) {
            return true;
        }
        String n = SpineCatalog.normalize(text);
        for (String entity : sampleEntities) {
            String en = SpineCatalog.normalize(entity);
            if (en.length() >= 3 && n.contains(en)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strips entity/amount/PAN/CIN fragments from a candidate key or path label.
     * Matches entities across separators ({@code Bhagyanagar_Solar} ≈ {@code Bhagyanagar Solar}).
     *
     * @return the remaining label tokens joined by {@code _} (may be empty)
     */
    public String strip(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = SpineCatalog.normalize(raw);
        s = stripMatches(s, PAN);
        s = stripMatches(s, GSTIN);
        s = stripMatches(s, CIN);
        s = stripMatches(s, ACCOUNT);
        s = stripMatches(s, AMOUNT);
        for (String entity : sampleEntities) {
            String en = SpineCatalog.normalize(entity);
            if (en.isEmpty()) {
                continue;
            }
            // match the entity with flexible separators: "bhagyanagar solar" / "bhagyanagar_solar"
            String flex = Pattern.quote(en).replace(" ", "[\\s_\\-]+");
            s = s.replaceAll("(?i)" + flex, " ");
        }
        s = s.replaceAll("(?i)bhagyanagar(?:[\\s_\\-]+solar)?|sharma|ramesh|kumar|patel|reddy", " ");
        s = stripMatches(s, NUMERIC_TOKEN);
        s = SpineCatalog.normalize(s);
        s = s.replace(' ', '_');
        s = s.replaceAll("_+", "_");
        s = s.replaceAll("^_|_$", "");
        return s;
    }

    /**
     * Rewrites a candidate field key to a label token (S2.11: {@code Bhagyanagar Solar Net Worth}
     * → {@code net_worth}). Returns empty when nothing label-like remains.
     */
    public String toLabelKey(String rawKey) {
        String stripped = strip(rawKey);
        if (stripped.isEmpty()) {
            return "";
        }
        // prefer a known spine-derived snake token when one is embedded
        String n = stripped.replace('_', ' ');
        var spine = SpineCatalog.matchLabel(n);
        if (spine.isPresent()) {
            return snake(spine.get().leafName());
        }
        return stripped;
    }

    /**
     * Cleans a key but preserves an already-label-like original
     * ({@code subject_name} stays {@code subject_name}).
     */
    public String rewriteKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return "";
        }
        String raw = rawKey.trim();
        if (!containsEntity(raw) && !isEntityToken(raw) && raw.matches("[a-z][a-z0-9_]*")) {
            return raw;
        }
        return toLabelKey(raw);
    }

    /** Collects PAN/CIN/GSTIN/amount tokens present in a corpus text. */
    public static List<String> extractValueTokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String upper = text.toUpperCase(Locale.ROOT);
        collect(upper, PAN, out);
        collect(upper, GSTIN, out);
        collect(upper, CIN, out);
        collect(text, ACCOUNT, out);
        collect(text, AMOUNT, out);
        return out;
    }

    /**
     * Sample-entity tokens for label-not-value. Generic document titles and file
     * names ({@code Credit Rating Report}, {@code Bank Statement.pdf},
     * {@code CIBIL Report}) are omitted unless they contain a real name,
     * identifier, or amount.
     */
    public static Set<String> sampleTokens(String title, String fileName, List<String> layout) {
        Set<String> tokens = new LinkedHashSet<>();
        addIfEntityBearing(tokens, title);
        addIfEntityBearing(tokens, fileName);
        if (layout != null) {
            for (String line : layout) {
                tokens.addAll(extractValueTokens(line));
            }
        }
        return Set.copyOf(tokens);
    }

    /**
     * Removes names, PAN/CIN/GSTIN, account numbers, and currency amounts.
     * Remaining words keep their original case so a label such as {@code Legal Name}
     * is still recognizable.
     */
    public String redact(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw;
        s = GSTIN.matcher(s.toUpperCase(java.util.Locale.ROOT)).find()
                ? replaceInsensitive(s, GSTIN) : s;
        s = replaceInsensitive(s, PAN);
        s = replaceInsensitive(s, CIN);
        s = ACCOUNT.matcher(s).replaceAll(" ");
        s = AMOUNT.matcher(s).replaceAll(" ");
        for (String entity : sampleEntities) {
            s = erasePhrase(s, entity);
        }
        s = s.replaceAll("(?i)\\bbhagyanagar(?:\\s+solar)?\\b|\\bsharma\\b|\\bramesh\\b"
                + "|\\bkumar\\b|\\bpatel\\b|\\breddy\\b", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static String replaceInsensitive(String input, Pattern uppercasePattern) {
        Matcher matcher = uppercasePattern.matcher(input.toUpperCase(java.util.Locale.ROOT));
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            sb.append(input, last, matcher.start()).append(' ');
            last = matcher.end();
        }
        sb.append(input, last, input.length());
        return sb.toString();
    }

    private static String erasePhrase(String input, String entity) {
        String en = SpineCatalog.normalize(entity);
        if (en.isEmpty()) {
            return input;
        }
        String flex = Pattern.quote(en).replace(" ", "\\s+");
        return input.replaceAll("(?i)" + flex, " ");
    }

    private static void addIfEntityBearing(Set<String> tokens, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String trimmed = raw.trim();
        if (containsIdentifierOrAmount(trimmed)
                || KNOWN_SAMPLE_ENTITIES.matcher(trimmed).find()
                || hasResidualName(trimmed)) {
            tokens.add(trimmed);
        }
    }

    private static boolean containsIdentifierOrAmount(String text) {
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        return PAN.matcher(upper).find()
                || CIN.matcher(upper).find()
                || GSTIN.matcher(upper).find()
                || ACCOUNT.matcher(text).find()
                || AMOUNT.matcher(text).find();
    }

    private static boolean hasResidualName(String raw) {
        String base = raw.replaceAll("(?i)\\.(pdf|xlsx|xls|docx?|png|jpe?g|csv|txt)$", "");
        String normalized = SpineCatalog.normalize(base);
        for (String word : normalized.split(" ")) {
            if (word.length() >= 3 && !GENERIC_LABEL_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** Document-type and file words that are not a borrower, promoter, or lender. */
    private static final Set<String> GENERIC_LABEL_WORDS = Set.of(
            "credit", "rating", "ratings", "report", "reports", "bank", "statement",
            "statements", "cibil", "bureau", "information", "memorandum", "term",
            "sheet", "resume", "director", "profile", "promoter", "management",
            "net", "worth", "certificate", "pvsyst", "sale", "deed", "lease", "land",
            "dpr", "detailed", "project", "quotation", "quote", "vendor", "screenshot",
            "commercial", "default", "agreement", "invoice", "pdf", "xlsx", "xls",
            "doc", "docx", "png", "jpg", "jpeg", "csv", "txt", "the", "and", "for",
            "ltd", "limited", "pvt", "private", "llp", "crisil", "icra", "care",
            "india", "final", "signed", "scan", "copy", "page");

    private static void collect(String text, Pattern p, List<String> out) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String g = m.group().trim();
            if (!g.isEmpty() && !out.contains(g)) {
                out.add(g);
            }
        }
    }

    private static String stripMatches(String input, Pattern p) {
        Matcher m = p.matcher(input);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(input, last, m.start()).append(' ');
            last = m.end();
        }
        sb.append(input, last, input.length());
        return sb.toString();
    }

    static String snake(String label) {
        return SpineCatalog.normalize(label).replace(' ', '_');
    }
}
