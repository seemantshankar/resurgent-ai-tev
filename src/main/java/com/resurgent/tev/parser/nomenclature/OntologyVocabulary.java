package com.resurgent.tev.parser.nomenclature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The shared nomenclature vocabulary: the normalized-label rule, the alias index it
 * builds, the paths a binder may choose, and the gate that admits a novel leaf.
 *
 * <p>Both sides of the platform resolve labels through this one class — the Excel
 * model's Layer B binding and the document pipeline's fact binding (issue #153) —
 * so a label cannot mean one path on the financial model and another on a document.
 */
public final class OntologyVocabulary {

    public static final String LEGAL_NAME = "Project Identity > Legal Name";
    public static final String CONSTITUTION = "Project Identity > Constitution / Entity Type";
    public static final String PARTNERS = "Project Identity > Partners / Promoters";
    public static final String ADDRESS = "Project Identity > Address";
    public static final String POWER = "Project > Power Connection";
    public static final String MANPOWER = "Project > Manpower";
    public static final String CAPACITY = "Project > Installed Capacity";

    public static final String FRAME_TITLE = "Frame > Schedule Title";
    public static final String FRAME_ANNEXURE = "Frame > Annexure";
    public static final String FRAME_BANNER = "Frame > Section Banner";
    public static final String FRAME_PERIOD = "Frame > Period";
    public static final String FRAME_SCALE = "Frame > Scale";
    public static final String FRAME_MARK = "Frame > Field Mark";
    public static final String FRAME_BLANK = "Frame > Blank";

    private static final Pattern CATEGORY = Pattern.compile("^[A-Za-z][A-Za-z0-9 /&'\\-]{0,48}$");

    /** ADR 0022 §2: a {@code Less: …} / {@code Add: …} row qualifier, never a category. */
    private static final Pattern QUALIFIER = Pattern.compile("^(LESS|ADD)\\b");

    /** ADR 0022 §2: the count column of a line item — "No. of", "Quantity", "Units". */
    private static final Pattern COUNT = Pattern.compile(
            "^(NO|NOS|NO OF|NUMBER OF|QUANTITY|QTY|UNIT|UNITS|WEIGHT|SIZE|DIMENSION)\\b");

    /** Who supplied or made the line, stated as a word or as a trailing proper name. */
    private static final Pattern ATTRIBUTION = Pattern.compile(
            "\\b(SUPPLIER|SUPPLIERS|VENDOR|VENDORS|MANUFACTURER|M/S|M S|O/S|W/E|MAKE|BRAND"
                    + "|TRADER|DEALER|AGENT|AGENCY|FIRM)\\b");

    /** An attribution that names itself: a spaced dash, then a two-word proper name. */
    private static final Pattern NAMED_ATTRIBUTION = Pattern.compile(
            "\\s[-–]\\s+[A-Z][a-z]+(\\s+[A-Z][a-z]+)+");

    /** A measurement left in the leaf: a rate, a quantity, a specification. */
    private static final Pattern MEASUREMENT = Pattern.compile("[0-9@%]");

    /** ADR 0022 §2: formula errors that leak from an Excel cell into a label. */
    private static final Pattern FORMULA_ERROR = Pattern.compile(
            "#(REF!|VALUE!|DIV/0!|NAME\\?|NUM!|NULL!|N/A|CALC!|SPILL!)",
            Pattern.CASE_INSENSITIVE);

    private OntologyVocabulary() {}

    /** Normalized label — upper case, apostrophes dropped, punctuation becomes space. */
    public static String norm(String text) {
        if (text == null) {
            return "";
        }
        return text.toUpperCase(Locale.ROOT)
                .replace("'", "")
                .replace("’", "")
                .replaceAll("[^A-Z0-9&/ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** The frozen seed vocabulary: every spine, hotel-pack and project-fact label. */
    public static Map<String, String> aliasIndex() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String path : NomenclatureSeed.SPINE_PATHS) {
            putAlias(aliases, NomenclatureSeed.nameOf(path), path);
            putAlias(aliases, path, path);
        }
        for (NomenclatureAlias alias : NomenclatureSeed.SPINE_ALIASES) {
            putAlias(aliases, alias.aliasText(), alias.leafPath());
        }
        for (String path : NomenclatureSeed.HOTEL_LEAVES) {
            putAlias(aliases, NomenclatureSeed.nameOf(path), path);
        }
        for (NomenclatureAlias alias : NomenclatureSeed.HOTEL_ALIASES) {
            putAlias(aliases, alias.aliasText(), alias.leafPath());
        }
        for (ProjectFactField field : ProjectFactSeed.FIELDS) {
            putAlias(aliases, field.name(), field.path());
            putAlias(aliases, field.path(), field.path());
            putAlias(aliases, NomenclatureSeed.nameOf(field.path()), field.path());
        }
        return Map.copyOf(aliases);
    }

    /** Alias index from a living ontology slice, then the seed defaults. */
    public static Map<String, String> aliasIndex(Map<String, String> living) {
        Map<String, String> aliases = new LinkedHashMap<>(aliasIndex());
        if (living != null) {
            for (Map.Entry<String, String> entry : living.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                putAlias(aliases, entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(aliases);
    }

    /** Every path a binder may choose, before a mandate grows any leaf of its own. */
    public static List<String> allowedPaths() {
        List<String> paths = new ArrayList<>();
        paths.addAll(NomenclatureSeed.SPINE_PATHS);
        paths.addAll(NomenclatureSeed.HOTEL_LEAVES);
        for (ProjectFactField field : ProjectFactSeed.FIELDS) {
            paths.add(field.path());
        }
        paths.add(FRAME_TITLE);
        paths.add(FRAME_ANNEXURE);
        paths.add(FRAME_BANNER);
        paths.add(FRAME_PERIOD);
        paths.add(FRAME_SCALE);
        paths.add(FRAME_MARK);
        paths.add(FRAME_BLANK);
        return List.copyOf(paths);
    }

    /** The seed paths plus every path the living ontology has grown. */
    public static List<String> allowedPaths(List<String> living) {
        LinkedHashMap<String, Boolean> paths = new LinkedHashMap<>();
        for (String path : allowedPaths()) {
            paths.put(path, Boolean.TRUE);
        }
        if (living != null) {
            for (String path : living) {
                if (path != null && !path.isBlank()) {
                    paths.put(path, Boolean.TRUE);
                }
            }
        }
        return List.copyOf(paths.keySet());
    }

    /**
     * Whether a proposed soft leaf copies its row instead of naming a category
     * (ADR 0022 §2): a formula error, a supplier or make, a tax rate, a quantity or
     * "No. of", a specification, or a {@code Less:}/{@code Add:} qualifier. Such a leaf can
     * never be matched again — supplier names differ per mandate — so a binder refuses it
     * rather than staging it into the one shared nomenclature.
     *
     * <p>This is a refusal list, not a whitelist: a short category name still mints
     * (ADR 0022 §3), and a leaf that is really a known catalogue item resolves through the
     * alias index before this rule is ever consulted.
     *
     * @param leaf the proposed leaf segment, as the model wrote it
     * @return true when the leaf is a transcription of the row, not a category
     */
    public static boolean transcribedLeaf(String leaf) {
        if (leaf == null || leaf.isBlank()) {
            return false;
        }
        String raw = leaf.trim();
        String text = norm(raw);
        return QUALIFIER.matcher(text).find()
                || COUNT.matcher(text).find()
                || ATTRIBUTION.matcher(text).find()
                || NAMED_ATTRIBUTION.matcher(raw).find()
                || MEASUREMENT.matcher(raw).find()
                || FORMULA_ERROR.matcher(raw).find();
    }

    /**
     * ADR 0022 §2's escape hatch, consulted before a transcribed leaf is refused: the one
     * catalogue path a label names. A label the alias index marks ambiguous names no path,
     * so a transcription that could mean two heads is refused rather than guessed.
     *
     * @param label the proposed leaf or row label, as the model wrote it
     * @param hardAliases the hard-catalogue alias index (seed, spine, and industry; excluding mandate_soft)
     * @return the catalogue path this label uniquely names, or null
     */
    private static String catalogueHit(String label, Map<String, String> hardAliases) {
        if (label == null || label.isBlank() || hardAliases == null) {
            return null;
        }
        String hit = hardAliases.get(norm(label));
        return hit == null || hit.isBlank() ? null : hit;
    }

    /**
     * ADR 0022 §2 applied to a leaf the ontology does not carry yet, in one place so both
     * binders answer the same way: the path to bind {@code path} to — itself when the leaf
     * names a category, or the catalogue leaf it uniquely aliases when the leaf is a
     * transcription — and null when the leaf copies its row and nothing names it.
     *
     * @param path the proposed {@code parent > leaf} path
     * @param hardAliases the hard-catalogue alias index (seed, spine, and industry; excluding mandate_soft)
     * @return the path the binder may use, or null to refuse
     */
    public static String bindablePath(String path, Map<String, String> hardAliases) {
        if (path == null || path.isBlank()) {
            return path;
        }
        String trimmed = path.trim();
        int sep = trimmed.lastIndexOf(" > ");
        String leaf = sep < 0 ? trimmed : trimmed.substring(sep + 3);
        if (!transcribedLeaf(leaf)) {
            return trimmed;
        }
        return catalogueHit(leaf, hardAliases);
    }

    /**
     * Whether a path may be bound under this root: an existing allowed path, or a
     * category-shaped new leaf hanging off a known mid-level.
     */
    public static boolean allowed(String root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return false;
        }
        if ("frame".equals(root)) {
            return path.equals(FRAME_TITLE)
                    || path.equals(FRAME_ANNEXURE)
                    || path.equals(FRAME_BANNER)
                    || path.equals(FRAME_PERIOD)
                    || path.equals(FRAME_SCALE)
                    || path.equals(FRAME_MARK)
                    || path.equals(FRAME_BLANK);
        }
        if ("identity".equals(root)) {
            return path.equals(LEGAL_NAME)
                    || path.equals(CONSTITUTION)
                    || path.equals(PARTNERS)
                    || path.equals(ADDRESS);
        }
        if (!"economic".equals(root) || path.startsWith("Frame > ")
                || path.startsWith("Project Identity > ")) {
            return false;
        }
        if (allowedPaths().contains(path)) {
            return true;
        }
        int sep = path.lastIndexOf(" > ");
        if (sep <= 0) {
            return false;
        }
        String parent = path.substring(0, sep);
        String leaf = path.substring(sep + 3);
        boolean knownParent = parent.equals("Cash Flow")
                || parent.equals("Profit & Loss")
                || parent.equals("Balance Sheet")
                || parent.equals("Project Cost")
                || parent.equals("Means of Finance")
                || NomenclatureSeed.SPINE_PATHS.contains(parent);
        return knownParent && CATEGORY.matcher(leaf).matches()
                && !leaf.contains("!") && !leaf.contains("#");
    }

    /**
     * The root a shared path belongs to, exactly as {@link #allowed} reads it: an
     * identity head, frame furniture, or an economic line. A bound fact stores its
     * path under this root, so both sides compose the same {@code root > path} key.
     */
    public static String rootFor(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (path.startsWith("Frame > ")) {
            return "frame";
        }
        if (path.startsWith("Project Identity > ")) {
            return "identity";
        }
        return "economic";
    }

    /**
     * Adds one alias under the shared collision rule: a label that names two paths
     * becomes ambiguous (empty) rather than silently replacing its earlier mapping.
     */
    public static void putAlias(Map<String, String> aliases, String text, String path) {
        String key = norm(text);
        if (key.isBlank()) {
            return;
        }
        String existing = aliases.get(key);
        if (existing == null) {
            aliases.put(key, path);
        } else if (existing.isBlank() || !existing.equals(path)) {
            aliases.put(key, "");
        }
    }
}
