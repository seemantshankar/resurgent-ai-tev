package com.resurgent.tev.doc.extract;

import com.resurgent.tev.doc.induce.EntityTokenFilter;
import com.resurgent.tev.doc.induce.SpineCatalog;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Derives pack-style field keys from label text on a labeled line (ADR 0036). */
final class LabeledLineFieldKeys {

    private static final Pattern PAREN_ACRONYM =
            Pattern.compile("\\(([A-Z]{2,12})\\)\\s*$");

    private LabeledLineFieldKeys() {}

    /**
     * A schema field key for a label the pack does not name, or null when the label
     * is not label-like (entity token, empty after stripping).
     */
    static String fromLabel(String labelText) {
        if (labelText == null || labelText.isBlank()) {
            return null;
        }
        String trimmed = labelText.trim();
        Matcher acronym = PAREN_ACRONYM.matcher(trimmed);
        if (acronym.find()) {
            return acronym.group(1).toLowerCase(Locale.ROOT);
        }
        EntityTokenFilter filter = EntityTokenFilter.none();
        if (filter.isEntityToken(trimmed)) {
            return null;
        }
        // Exact spine match first: a short generic word (e.g. a bare "Project")
        // must never resolve via the miner's loose "longest contained label"
        // fallback, which would misfile it under an unrelated, longer financial
        // head such as "Project Cost" (ADR 0036, issue #205).
        var spine = SpineCatalog.matchLabelExact(trimmed);
        if (spine.isPresent()) {
            return snake(spine.get().leafName());
        }
        String key = filter.strip(trimmed);
        if (key.isEmpty()) {
            return null;
        }
        if (filter.isEntityToken(key.replace('_', ' '))) {
            return null;
        }
        return key.toLowerCase(Locale.ROOT);
    }

    private static String snake(String leafName) {
        return leafName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }
}
