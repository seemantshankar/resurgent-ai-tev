package com.resurgent.tev.doc.bind;

import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.induce.SpineCatalog;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.parser.nomenclature.LivingOntology;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import com.resurgent.tev.parser.nomenclature.OntologyVocabulary;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Binds facts from labeled lines the matched pack does not name (ADR 0036, #205). */
final class LabeledLineBinder {

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)^(?:n/?a|nil|none|tbd|pending|-+|—+)$");

    private static final Pattern PAREN_ACRONYM =
            Pattern.compile("\\(([A-Z]{2,12})\\)");

    private static final Pattern NUMERIC_FIELD_KEY =
            Pattern.compile("^[\\d,]+(?:\\.\\d+)?$");

    record Result(boolean bound, String livingPath, String aliasHint) {}

    private LabeledLineBinder() {}

    /**
     * A bare figure stored by {@code storeUnexplainedFigures} uses its number as
     * the field key. It stays a fact and must not become a mandate head (#205).
     */
    static boolean isUnexplainedFigure(FactRepository.FactBindRow row) {
        if (row.fieldKey() == null || row.valueText() == null) {
            return false;
        }
        String key = row.fieldKey().trim();
        String value = row.valueText().trim();
        if (!NUMERIC_FIELD_KEY.matcher(key).matches()) {
            return false;
        }
        String keyDigits = key.replace(",", "");
        String valueDigits = value.replace(",", "");
        return key.equals(value) || keyDigits.equals(valueDigits) || value.startsWith(key);
    }

    static Result bind(FactRepository facts, NomenclatureCatalog catalog, long mandateId,
            DocTypePack pack, LivingOntology living, FactRepository.FactBindRow row)
            throws SQLException {
        if (row.fieldKey() == null || row.fieldKey().isBlank()) {
            return new Result(false, null, null);
        }
        String value = row.valueText();
        if (value == null || value.isBlank() || PLACEHOLDER.matcher(value.trim()).matches()) {
            facts.updateFactBinding(row.factId(), row.ontologyPath(), row.pathRoot(),
                    "unbound", "not_a_value", "label is not a real value");
            return new Result(false, null, null);
        }
        String printedLabel = printedLabel(row);
        String labelPhrase = printedLabel != null
                ? printedLabel
                : row.fieldKey().replace('_', ' ');
        String aliasPath = resolveExistingPath(living, printedLabel, labelPhrase, row.fieldKey());
        if (aliasPath != null && !aliasPath.isBlank()) {
            String root = OntologyVocabulary.rootFor(aliasPath);
            facts.updateFactBinding(row.factId(), root + " > " + aliasPath, root, "bound", null,
                    "alias: " + labelPhrase);
            return new Result(true, aliasPath, labelPhrase);
        }
        String group = defaultGroup(pack);
        String measure = measureName(row.fieldKey(), labelPhrase);
        String relative = catalog.putMandateHead(mandateId, group, measure,
                aliasSynonyms(printedLabel, labelPhrase));
        String root = defaultRoot(pack);
        facts.updateFactBinding(row.factId(), root + " > " + relative, root, "bound", null,
                "mandate head: " + relative);
        return new Result(true, relative, labelPhrase);
    }

    private static String printedLabel(FactRepository.FactBindRow row) {
        String verbatim = row.verbatim();
        if (verbatim == null || verbatim.isBlank()) {
            return null;
        }
        int colon = verbatim.indexOf(':');
        if (colon > 2 && colon < 50) {
            return verbatim.substring(0, colon).trim();
        }
        return null;
    }

    private static String resolveExistingPath(LivingOntology living, String printedLabel,
            String labelPhrase, String fieldKey) {
        for (String phrase : aliasPhrases(printedLabel, labelPhrase, fieldKey)) {
            String path = living.aliases().get(OntologyVocabulary.norm(phrase));
            if (path != null && !path.isBlank()) {
                return path;
            }
        }
        if (printedLabel != null && !printedLabel.isBlank()) {
            var spine = SpineCatalog.matchLabelExact(printedLabel.trim());
            if (spine.isPresent()) {
                String path = spine.get().path();
                if (living.contains(path)) {
                    return path;
                }
            }
        }
        String acronym = acronymFrom(printedLabel != null ? printedLabel : labelPhrase);
        if (acronym != null) {
            String titled = acronym.toUpperCase(Locale.ROOT);
            for (String path : living.paths()) {
                if (path.endsWith(" > " + titled) || path.endsWith(" > " + acronym)) {
                    return path;
                }
            }
        }
        return null;
    }

    private static List<String> aliasPhrases(String printedLabel, String labelPhrase,
            String fieldKey) {
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        if (labelPhrase != null && !labelPhrase.isBlank()) {
            phrases.add(labelPhrase);
        }
        if (printedLabel != null && !printedLabel.isBlank()) {
            phrases.add(printedLabel);
        }
        if (fieldKey != null && !fieldKey.isBlank()) {
            phrases.add(fieldKey.replace('_', ' '));
        }
        String acronym = acronymFrom(printedLabel != null ? printedLabel : labelPhrase);
        if (acronym != null) {
            phrases.add(acronym);
        }
        return new ArrayList<>(phrases);
    }

    private static List<String> aliasSynonyms(String printedLabel, String labelPhrase) {
        LinkedHashSet<String> synonyms = new LinkedHashSet<>();
        if (labelPhrase != null && !labelPhrase.isBlank()) {
            synonyms.add(labelPhrase);
        }
        if (printedLabel != null && !printedLabel.isBlank()
                && !printedLabel.equalsIgnoreCase(labelPhrase)) {
            synonyms.add(printedLabel);
        }
        String acronym = acronymFrom(printedLabel != null ? printedLabel : labelPhrase);
        if (acronym != null) {
            synonyms.add(acronym);
        }
        return new ArrayList<>(synonyms);
    }

    private static String acronymFrom(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        Matcher matcher = PAREN_ACRONYM.matcher(label);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String defaultGroup(DocTypePack pack) {
        if ("certificate".equalsIgnoreCase(pack.fileRole())) {
            return "Identity";
        }
        if ("report".equalsIgnoreCase(pack.fileRole())) {
            return "Environment";
        }
        return "General";
    }

    private static String defaultRoot(DocTypePack pack) {
        if ("certificate".equalsIgnoreCase(pack.fileRole())) {
            return "identity";
        }
        if ("report".equalsIgnoreCase(pack.fileRole())) {
            return "technical";
        }
        return "regulatory";
    }

    private static String measureName(String fieldKey, String labelPhrase) {
        if (fieldKey.length() <= 5 && fieldKey.equals(fieldKey.toLowerCase(Locale.ROOT))) {
            return fieldKey.toUpperCase(Locale.ROOT);
        }
        if (!labelPhrase.isBlank()) {
            return titleCase(labelPhrase);
        }
        return titleCase(fieldKey.replace('_', ' '));
    }

    private static String titleCase(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String[] parts = raw.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) {
                out.append(' ');
            }
            if (part.length() <= 4 && part.equals(part.toUpperCase(Locale.ROOT))) {
                out.append(part);
            } else {
                out.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    out.append(part.substring(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return out.toString();
    }
}
