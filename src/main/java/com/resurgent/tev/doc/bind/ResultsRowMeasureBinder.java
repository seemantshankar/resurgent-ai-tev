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

    /**
     * Full-phrase aliases for measures the living master already names by acronym.
     * Initials are not used as a lookup key: Total Nitrogen and Total Nickel would
     * both become TN.
     */
    private static final Map<String, String> SEEDED_ACRONYM = Map.of(
            "BIOCHEMICAL OXYGEN DEMAND", "BOD",
            "CHEMICAL OXYGEN DEMAND", "COD");

    private ResultsRowMeasureBinder() {}

    record Result(
            boolean bound,
            String livingPath,
            String aliasHint,
            String leafKey,
            String pathRoot,
            List<String> extraAliases) {}

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
            return new Result(false, null, null, null, null, List.of());
        }
        String measure = measureName(phrase);
        boolean explicitAcronym = explicitAcronym(phrase, measure);
        String existing = existingPath(living, phrase, measure, explicitAcronym);
        String relative;
        List<String> extras = seededAliases(measure);
        if (existing != null) {
            relative = existing;
        } else {
            LinkedHashSet<String> aliases = new LinkedHashSet<>();
            aliases.add(phrase);
            aliases.add(measure);
            aliases.addAll(extras);
            relative = catalog.putMandateHead(mandateId, groupName(table), measure,
                    List.copyOf(aliases));
        }
        String root = pathRoot(table);
        facts.updateFactBinding(row.factId(), root + " > " + relative, root, "bound", null,
                "results-row measure: " + relative);
        String leafKey = measure.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
        return new Result(true, relative, phrase, leafKey, root, extras);
    }

    /** Table path leaf, so a water-quality table is not forced under Environment. */
    private static String groupName(ScheduleCells.TableRef table) {
        String path = table.path();
        if (path == null || path.isBlank() || "null".equals(path)) {
            return "Environment";
        }
        int sep = path.lastIndexOf(" > ");
        String leaf = (sep < 0 ? path : path.substring(sep + 3)).trim();
        return leaf.isBlank() ? "Environment" : leaf;
    }

    /** Same root for a new head and a reused one: the table root, never economic. */
    private static String pathRoot(ScheduleCells.TableRef table) {
        String root = table.pathRoot() == null || table.pathRoot().isBlank()
                ? "technical" : table.pathRoot();
        return "economic".equals(root) ? "technical" : root;
    }

    private static String existingPath(LivingOntology living, String phrase, String measure,
            boolean explicitAcronym) {
        String fromAlias = living.aliases().get(OntologyVocabulary.norm(phrase));
        if (fromAlias != null && !fromAlias.isBlank()) {
            return fromAlias;
        }
        if (!explicitAcronym) {
            return null;
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

    private static boolean explicitAcronym(String phrase, String measure) {
        if (PAREN_ACRONYM.matcher(phrase).find()) {
            return true;
        }
        if (phrase.length() <= 5 && phrase.equalsIgnoreCase(measure)) {
            return true;
        }
        return SEEDED_ACRONYM.containsKey(OntologyVocabulary.norm(phrase));
    }

    private static List<String> seededAliases(String measure) {
        List<String> extras = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : SEEDED_ACRONYM.entrySet()) {
            if (entry.getValue().equals(measure)) {
                extras.add(title(entry.getKey().toLowerCase(Locale.ROOT)));
            }
        }
        return List.copyOf(extras);
    }

    private static String measureName(String phrase) {
        Matcher paren = PAREN_ACRONYM.matcher(phrase);
        if (paren.find()) {
            return paren.group(1);
        }
        String seeded = SEEDED_ACRONYM.get(OntologyVocabulary.norm(phrase));
        if (seeded != null) {
            return seeded;
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
