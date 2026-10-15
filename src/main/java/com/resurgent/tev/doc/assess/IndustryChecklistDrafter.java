package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.checklist.ChecklistDefinition;
import com.resurgent.tev.doc.checklist.ChecklistItemDef;
import com.resurgent.tev.doc.checklist.ChecklistLoader;
import com.resurgent.tev.doc.checklist.ChecklistStore;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import com.resurgent.tev.doc.query.MandateQa;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code assess_checklist} industry step (M3-11, issue #144): an industry checklist
 * keyed by industry and country. When the mandate has an industry the in-scope
 * catalog carries no definition for, the model first-drafts one — every item with
 * the regulation or practice behind it, and an item whose basis cannot be named is
 * dropped, never stored. A brand-new industry is seeded as a starter list built
 * from the country spine, the closest known industries and the regulations the
 * model can name, labelled {@value #NEW_INDUSTRY_NOTE}.
 *
 * <p>The draft is published into the versioned checklist store beside the workspace
 * (the pack-store precedent), so it is reused on every later run and assessment of
 * the same mandate costs no further draft call.
 */
public final class IndustryChecklistDrafter {

    /** Label of a starter list drafted for an industry the platform has not seen. */
    public static final String NEW_INDUSTRY_NOTE = "new industry: first mandate";

    private static final String SYSTEM_HEADER = """
            You draft the industry checklist for the "%s" industry in %s for a \
            project-finance mandate. Every item must name the regulation or the \
            practice that requires it. An item whose basis you cannot name must be \
            omitted — it is dropped, never stored.
            Reply with ONE JSON object:
            {"items": [
              {"key": "<snake_case key>", "label": "<human label>",
               "level": "document"|"data_point",
               "granularity": "mandate"|"entity"|"site",
               "document_type": "<the expected document>",
               "basis": {"kind": "regulation"|"practice", "text": "<the named regulation or practice>"},
               "request": "<ask-the-client wording; {site} becomes the site name>"}
            ]}
            A "data_point" item additionally names "field_key" or "ontology_path" and \
            is expected inside its document_type. A "site"-granularity item is \
            expected once per site. Name only requirements you can justify.""";

    private static final String NEW_INDUSTRY_HEADER = """
            This industry is new to the platform ("%s"): build the starter checklist \
            from the country spine items, the closest known industries' items and the \
            regulations you can name.""";

    private static final int MAX_COMPLETION_TOKENS = 2_000;

    private IndustryChecklistDrafter() {}

    /**
     * Drafts and publishes a definition for every mandate industry the in-scope
     * catalog does not already cover. The catalog is extended as drafts publish, so
     * each later industry may borrow from the ones before it.
     *
     * @param industries the mandate's recorded industries, oldest first
     * @param country the mandate's country record, or null when none was detected
     * @param dbPath workspace database path (the store sits beside it)
     * @param catalog the loaded definitions (spine plus the store)
     * @param client document LLM seam; never null here
     * @param usageSink receives every model call's usage
     * @return the definitions drafted in this run, in industry order
     * @throws Exception if the store cannot be written or read back
     */
    public static List<ChecklistDefinition> draftMissing(List<MandateQa.IndustryRow> industries,
            MandateQa.CountryRow country, Path dbPath, Collection<ChecklistDefinition> catalog,
            DocLlmClient client, Consumer<DocLlmClient.Usage> usageSink) throws Exception {
        String countryCode = ChecklistAssessor.countryCode(country);
        if (countryCode == null || industries.isEmpty()) {
            // Keyed by industry AND country: with no country, no key to draft under.
            return List.of();
        }
        List<ChecklistDefinition> known = new ArrayList<>(catalog);
        List<ChecklistDefinition> drafted = new ArrayList<>();
        for (MandateQa.IndustryRow row : industries) {
            String key = IndustryScan.normalize(row.industry());
            if (key.isEmpty() || covered(known, key, countryCode)) {
                continue;
            }
            Optional<String> draft = ask(client, row, countryCode,
                    country == null ? countryCode : country.country(), known, usageSink);
            if (draft.isEmpty()) {
                continue;
            }
            List<ChecklistItemDef> items = validate(draft.get(), key, row.industry());
            if (items.isEmpty()) {
                System.err.println("[doc-assess] industry checklist draft for '" + key
                        + "' carried no item with a basis — nothing published");
                continue;
            }
            ChecklistDefinition definition = new ChecklistDefinition(
                    codeFor(countryCode, key), "1.0.0", "industry", countryCode,
                    List.of(key), items,
                    row.isNewIndustry() ? NEW_INDUSTRY_NOTE : null);
            Path file = new ChecklistStore(ChecklistStore.storeRoot(dbPath)).publish(definition);
            // What is assessed is exactly what was written and read back, not the
            // in-memory draft — the same discipline as the pack store.
            ChecklistDefinition published = ChecklistLoader.load(file);
            drafted.add(published);
            known.add(published);
        }
        return List.copyOf(drafted);
    }

    /** True when a definition for this industry and country is already in scope. */
    static boolean covered(Collection<ChecklistDefinition> catalog, String industryKey,
            String countryCode) {
        String key = ChecklistAssessor.normaliseIndustry(industryKey);
        for (ChecklistDefinition definition : catalog) {
            if (definition.isSpine() || !definition.industries().stream()
                    .map(ChecklistAssessor::normaliseIndustry).anyMatch(key::equals)) {
                continue;
            }
            if (countryCode.equals(definition.country())) {
                return true;
            }
        }
        return false;
    }

    /** The definition code of a drafted industry checklist, e.g. {@code in.green_hydrogen}. */
    static String codeFor(String countryCode, String industryKey) {
        return countryCode.toLowerCase(Locale.ROOT) + "."
                + industryKey.replaceAll("[^a-z0-9]+", "_");
    }

    /** Keeps only the drafted items that pass the loader's rules — basis included. */
    static List<ChecklistItemDef> validate(String draftJson, String industryKey,
            String industryLabel) {
        JsonNode root;
        try {
            root = DocLlmJson.object(draftJson);
        } catch (DocLlmException e) {
            System.err.println("[doc-assess] industry checklist draft for '" + industryKey
                    + "' is not interpretable: " + e.getMessage());
            return List.of();
        }
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<ChecklistItemDef> validated = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode item : items) {
            index++;
            try {
                ChecklistItemDef parsed = ChecklistLoader.parseItem(item,
                        "draft " + industryLabel + " items[" + index + "]");
                if (keys.add(parsed.key())) {
                    validated.add(parsed);
                }
            } catch (ChecklistLoader.ChecklistValidationException e) {
                // The ticket's rule: an item without a basis (or otherwise invalid)
                // is dropped, never stored — the message names why.
                System.err.println("[doc-assess] dropping drafted checklist item: "
                        + e.getMessage());
            }
        }
        return List.copyOf(validated);
    }

    /** Asks the model for the industry's first draft; the reply is never trusted blindly. */
    private static Optional<String> ask(DocLlmClient client, MandateQa.IndustryRow row,
            String countryCode, String countryName, List<ChecklistDefinition> known,
            Consumer<DocLlmClient.Usage> usageSink) {
        try {
            DocLlmClient.Completion completion = client.complete(
                    system(row, countryCode, countryName, known), user(row, countryName),
                    MAX_COMPLETION_TOKENS);
            usageSink.accept(completion.usage());
            return Optional.ofNullable(completion.content());
        } catch (DocLlmException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] industry checklist draft failed: " + e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] industry checklist draft failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** The draft instruction: the industry's own items, seeded when the industry is new. */
    static String system(MandateQa.IndustryRow row, String countryCode, String countryName,
            List<ChecklistDefinition> known) {
        StringBuilder system = new StringBuilder(String.format(Locale.ROOT,
                SYSTEM_HEADER, row.industry(), countryName));
        if (row.isNewIndustry()) {
            system.append('\n').append(String.format(Locale.ROOT, NEW_INDUSTRY_HEADER,
                    NEW_INDUSTRY_NOTE));
            Map<String, List<String>> spineLabels = new LinkedHashMap<>();
            Map<String, List<String>> industryLabels = new LinkedHashMap<>();
            for (ChecklistDefinition definition : known) {
                if (!countryCode.equals(definition.country())) {
                    continue;
                }
                if (definition.isSpine()) {
                    spineLabels.put(definition.code(), definition.items().stream()
                            .map(ChecklistItemDef::label).toList());
                } else {
                    industryLabels.put(String.join(", ", definition.industries()),
                            definition.items().stream()
                                    .map(ChecklistItemDef::label).toList());
                }
            }
            for (Map.Entry<String, List<String>> spine : spineLabels.entrySet()) {
                system.append("\nCountry spine items already asked for: ")
                        .append(String.join("; ", spine.getValue())).append('.');
            }
            for (Map.Entry<String, List<String>> industry : industryLabels.entrySet()) {
                system.append("\nKnown industry checklist (").append(industry.getKey())
                        .append(") you may borrow from: ")
                        .append(String.join("; ", industry.getValue())).append('.');
            }
        }
        return system.toString();
    }

    private static String user(MandateQa.IndustryRow row, String countryName) {
        return "Industry: " + row.industry() + "\nCountry: " + countryName
                + "\nDraft the industry checklist now.";
    }
}
