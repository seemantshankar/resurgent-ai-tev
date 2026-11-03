package com.resurgent.tev.doc.bind;

import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.parser.nomenclature.LivingOntology;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import com.resurgent.tev.parser.nomenclature.OntologyVocabulary;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Binds facts from labeled lines the matched pack does not name (ADR 0036, #205). */
final class LabeledLineBinder {

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)^(?:n/?a|nil|none|tbd|pending|-+|—+)$");

    record Result(boolean bound, String livingPath, String aliasHint) {}

    private LabeledLineBinder() {}

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
        String labelPhrase = row.fieldKey().replace('_', ' ');
        String norm = OntologyVocabulary.norm(labelPhrase);
        String aliasPath = living.aliases().get(norm);
        if (aliasPath != null && !aliasPath.isBlank()) {
            String root = OntologyVocabulary.rootFor(aliasPath);
            facts.updateFactBinding(row.factId(), root + " > " + aliasPath, root, "bound", null,
                    "alias: " + labelPhrase);
            return new Result(true, aliasPath, labelPhrase);
        }
        String group = defaultGroup(pack);
        String measure = measureName(row.fieldKey(), labelPhrase);
        String relative = catalog.putMandateHead(mandateId, group, measure,
                List.of(labelPhrase));
        String root = defaultRoot(pack);
        facts.updateFactBinding(row.factId(), root + " > " + relative, root, "bound", null,
                "mandate head: " + relative);
        return new Result(true, relative, labelPhrase);
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
