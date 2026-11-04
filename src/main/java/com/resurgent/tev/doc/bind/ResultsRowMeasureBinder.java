package com.resurgent.tev.doc.bind;

import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.extract.ScheduleCells;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.parser.nomenclature.LivingOntology;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import com.resurgent.tev.parser.nomenclature.OntologyVocabulary;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names a results-row measure on the living master (ADR 0036, #207). The table
 * stays a schedule; only the bound path becomes {@code Environment > BOD}.
 */
final class ResultsRowMeasureBinder {

    private static final Pattern PAREN_ACRONYM =
            Pattern.compile("\\(([A-Z]{2,12})\\)");

    private ResultsRowMeasureBinder() {}

    record Result(boolean bound, String livingPath, String aliasHint, String leafKey) {}

    static boolean isParameterKeyed(DocTypePack pack, ScheduleCells.TableRef table) {
        if (pack == null || table == null) {
            return false;
        }
        for (Map<String, Object> spec : pack.tables()) {
            if (!table.tableName().equals(String.valueOf(spec.get("name")))) {
                continue;
            }
            Object key = spec.get("key_column");
            return key != null && "parameter".equalsIgnoreCase(String.valueOf(key));
        }
        return false;
    }

    static Result bind(FactRepository facts, NomenclatureCatalog catalog, long mandateId,
            LivingOntology living, FactRepository.FactBindRow row, ScheduleCells.TableRef table)
            throws SQLException {
        String phrase = row.lineKey() == null ? "" : row.lineKey().trim();
        if (phrase.isBlank() || table.column().equalsIgnoreCase("parameter")) {
            return new Result(false, null, null, null);
        }
        String measure = measureName(phrase);
        String existing = existingPath(living, phrase, measure);
        String relative;
        if (existing != null) {
            relative = existing;
        } else {
            LinkedHashSet<String> aliases = new LinkedHashSet<>();
            aliases.add(phrase);
            aliases.add(measure);
            relative = catalog.putMandateHead(mandateId, "Environment", measure,
                    List.copyOf(aliases));
        }
        String root = table.pathRoot() == null || table.pathRoot().isBlank()
                ? "technical" : table.pathRoot();
        if ("economic".equals(root)) {
            root = "technical";
        }
        facts.updateFactBinding(row.factId(), root + " > " + relative, root, "bound", null,
                "results-row measure: " + relative);
        String leafKey = measure.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
        return new Result(true, relative, phrase, leafKey);
    }

    private static String existingPath(LivingOntology living, String phrase, String measure) {
        String fromAlias = living.aliases().get(OntologyVocabulary.norm(phrase));
        if (fromAlias != null && !fromAlias.isBlank()) {
            return fromAlias;
        }
        String fromMeasure = living.aliases().get(OntologyVocabulary.norm(measure));
        if (fromMeasure != null && !fromMeasure.isBlank()) {
            return fromMeasure;
        }
        String titled = measure.toUpperCase(Locale.ROOT);
        for (String path : living.paths()) {
            if (path.endsWith(" > " + titled) || path.endsWith(" > " + measure)) {
                return path;
            }
        }
        return null;
    }

    private static String measureName(String phrase) {
        Matcher paren = PAREN_ACRONYM.matcher(phrase);
        if (paren.find()) {
            return paren.group(1);
        }
        String folded = phrase.toLowerCase(Locale.ROOT);
        if (folded.contains("biochemical oxygen") || folded.equals("bod")) {
            return "BOD";
        }
        if (folded.contains("chemical oxygen") || folded.equals("cod")) {
            return "COD";
        }
        if (phrase.length() <= 5) {
            return phrase.toUpperCase(Locale.ROOT);
        }
        return title(phrase);
    }

    private static String title(String raw) {
        String[] parts = raw.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }
}
