package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.assess.ChecklistAssessor;
import com.resurgent.tev.doc.checklist.ChecklistDefinition;
import com.resurgent.tev.doc.checklist.ChecklistItemDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Checklist learning from client document sets (M3-15, issue #148). The
 * deterministic engine — fed one observation per mandate workspace — reshapes the
 * versioned checklist definitions:
 *
 * <ul>
 *   <li>an <b>industry</b> checklist (industry + country) gains a document type
 *       seen in at least {@value #DEFAULT_MIN_OBSERVED_MANDATES} of its mandates
 *       and in a strict majority of them, as an {@code observed} item naming the
 *       source mandates;</li>
 *   <li>an industry item <b>never provided</b> across at least
 *       {@value #DEFAULT_MIN_MISSING_MANDATES} of its mandates is downgraded to
 *       optional, with the reason and the missing mandates recorded on the item;</li>
 *   <li>a document type seen in client sets from at least
 *       {@value #DEFAULT_MIN_INDUSTRIES_FOR_SPINE} different industries may join
 *       the country <b>spine</b> — only when the {@link
 *       ProjectTypeIndependenceJudge} judges it project-type independent, and the
 *       judgement's reason is recorded on the item.</li>
 * </ul>
 *
 * <p>Thresholds are configuration with defaults. Every change is expressed as a
 * new definition version — the caller publishes it, never edits history — and
 * carries its source mandates and reason. There is no human approval step: the
 * system decides and records why.
 */
public final class ChecklistLearner {

    /** A type seen in fewer mandates than this can never be added (config default). */
    public static final int DEFAULT_MIN_OBSERVED_MANDATES = 2;

    /** An item missing from this many industry mandates becomes optional (config default). */
    public static final int DEFAULT_MIN_MISSING_MANDATES = 2;

    /** A spine candidate needs this many different industries (config default). */
    public static final int DEFAULT_MIN_INDUSTRIES_FOR_SPINE = 2;

    /**
     * The learning thresholds — config, not code: an induction mandate retunes them
     * without a release.
     *
     * @param minObservedMandates mandates that must have provided a document type
     *     before its industry checklist may gain it
     * @param minMissingMandates industry mandates a document type must be missing
     *     from before its item is downgraded to optional
     * @param minIndustriesForSpine different industries that must have provided a
     *     document type before the spine may gain it (the judge still decides)
     */
    public record Config(
            int minObservedMandates,
            int minMissingMandates,
            int minIndustriesForSpine) {

        public Config {
            minObservedMandates = Math.max(1, minObservedMandates);
            minMissingMandates = Math.max(1, minMissingMandates);
            minIndustriesForSpine = Math.max(1, minIndustriesForSpine);
        }

        /** The shipped defaults: 2 mandates observed, 2 missing, 2 industries. */
        public static Config defaults() {
            return new Config(DEFAULT_MIN_OBSERVED_MANDATES, DEFAULT_MIN_MISSING_MANDATES,
                    DEFAULT_MIN_INDUSTRIES_FOR_SPINE);
        }
    }

    /**
     * One mandate workspace's contribution: what it was classified as and which
     * document types its client document set provided.
     *
     * @param mandateId the mandate scope id
     * @param country the mandate's country code (e.g. {@code IN}), or null when none
     * @param industries the mandate's recorded industries as stated (folded here)
     * @param documentTypes the document types its documents were classified as
     */
    public record MandateObservation(
            long mandateId,
            String country,
            List<String> industries,
            List<String> documentTypes) {

        public MandateObservation {
            industries = industries == null ? List.of() : List.copyOf(industries);
            documentTypes = documentTypes == null ? List.of() : List.copyOf(documentTypes);
        }
    }

    /**
     * The learning input: the observed mandates and the currently loaded catalog
     * (seed spine plus the store's definitions).
     */
    public record Inputs(List<MandateObservation> mandates, List<ChecklistDefinition> catalog) {

        public Inputs {
            mandates = mandates == null ? List.of() : List.copyOf(mandates);
            Objects.requireNonNull(catalog, "catalog");
            catalog = List.copyOf(catalog);
        }
    }

    /**
     * One change to one item, with the source mandates, the reason, and the note
     * recorded on the item itself.
     *
     * @param kind {@code added} | {@code made_optional} | {@code promoted}
     * @param itemKey the item key
     * @param documentType the document type the change is about
     * @param sourceMandateIds the mandates that provided it ({@code added}, {@code
     *     promoted}) or that did not ({@code made_optional})
     * @param reason one line why the change was made
     * @param note the note stored on the item (e.g. why it became optional, or the
     *     judgement behind a promotion), or null
     */
    public record ItemChange(
            String kind,
            String itemKey,
            String documentType,
            List<Long> sourceMandateIds,
            String reason,
            String note) {

        public ItemChange {
            sourceMandateIds = sourceMandateIds == null ? List.of()
                    : List.copyOf(sourceMandateIds);
        }
    }

    /**
     * One definition reshaped into a new version. The caller publishes {@code
     * definition} as {@code {code}@{newVersion}.yaml}.
     *
     * @param kind {@code industry} | {@code spine}
     * @param code the definition code
     * @param previousVersion the version the changes were computed against
     * @param newVersion the next minor version carrying the changes
     * @param definition the complete new definition
     * @param changes the item changes, in application order
     */
    public record DefinitionUpdate(
            String kind,
            String code,
            String previousVersion,
            String newVersion,
            ChecklistDefinition definition,
            List<ItemChange> changes) {

        public DefinitionUpdate {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    /**
     * The learning outcome.
     *
     * @param updates one update per definition that changed; empty when nothing did
     * @param judgements the project-type judgements made (empty when there were no
     *     spine candidates to judge)
     */
    public record Outcome(
            List<DefinitionUpdate> updates,
            List<ProjectTypeIndependenceJudge.Judgement> judgements) {

        public Outcome {
            updates = updates == null ? List.of() : List.copyOf(updates);
            judgements = judgements == null ? List.of() : List.copyOf(judgements);
        }
    }

    private ChecklistLearner() {}

    /**
     * Learns from the observed mandates against the loaded catalog.
     *
     * @param inputs the observed mandates and the current catalog (required)
     * @param judge project-type independence seam (required; called once, and only
     *     when there are spine candidates)
     * @param config the thresholds (required)
     * @return the definition updates and judgements; nothing when nothing changed
     */
    public static Outcome learn(Inputs inputs, ProjectTypeIndependenceJudge judge,
            Config config) {
        List<MandateObservation> mandates = inputs.mandates().stream()
                .map(ChecklistLearner::fold)
                .toList();

        List<DefinitionUpdate> updates = new ArrayList<>();
        for (ChecklistDefinition definition : inputs.catalog()) {
            if (definition.isSpine()) {
                continue;
            }
            DefinitionUpdate update = learnIndustry(definition, mandates, config);
            if (update != null) {
                updates.add(update);
            }
        }

        // Spine learning reads the client sets directly, independent of the industry
        // updates: the assessor dedupes items by key across the in-scope definitions.
        List<ProjectTypeIndependenceJudge.Judgement> judgements = new ArrayList<>();
        for (ChecklistDefinition definition : inputs.catalog()) {
            if (!definition.isSpine()) {
                continue;
            }
            DefinitionUpdate spine = learnSpine(definition, mandates, judge, config, judgements);
            if (spine != null) {
                updates.add(spine);
            }
        }
        return new Outcome(updates, judgements);
    }

    /**
     * Industry learning for one definition: the mandates of its country and
     * industries are the client sets; additions need the observed and majority
     * thresholds, downgrades need the missing threshold.
     */
    private static DefinitionUpdate learnIndustry(ChecklistDefinition definition,
            List<MandateObservation> mandates, Config config) {
        Set<String> definitionIndustries = definition.industries().stream()
                .map(ChecklistLearner::foldIndustryKey)
                .filter(key -> !key.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<MandateObservation> group = mandates.stream()
                .filter(m -> countryMatches(definition.country(), m.country())
                        && m.industries().stream().anyMatch(definitionIndustries::contains))
                .toList();
        if (group.isEmpty()) {
            return null;
        }
        int total = group.size();
        String industryLabel = String.join(", ", definitionIndustries);

        List<ItemChange> changes = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : observedTypes(group).entrySet()) {
            String documentType = entry.getKey();
            List<Long> sourceMandates = entry.getValue();
            if (sourceMandates.size() < config.minObservedMandates()
                    || sourceMandates.size() * 2 <= total) {
                continue;
            }
            String key = sanitizeKey(documentType);
            if (providesDocumentType(definition, documentType) || keyInUse(definition, key)) {
                continue;
            }
            String reason = "seen in the client document sets of " + sourceMandates.size()
                    + " of the " + total + " " + industryLabel + " mandate(s) read (mandates "
                    + mandateIds(sourceMandates) + ")";
            changes.add(new ItemChange("added", key, documentType, sourceMandates, reason, null));
        }
        for (ChecklistItemDef item : definition.items()) {
            if (!item.mandatory()) {
                continue;
            }
            List<Long> missing = group.stream()
                    .filter(m -> !m.documentTypes().contains(item.documentType()))
                    .map(MandateObservation::mandateId)
                    .sorted()
                    .toList();
            if (missing.size() < config.minMissingMandates()) {
                continue;
            }
            String reason = "not provided in " + missing.size() + " of the " + total + " "
                    + industryLabel + " mandate(s) read (mandates " + mandateIds(missing) + ")";
            changes.add(new ItemChange("made_optional", item.key(), item.documentType(), missing,
                    reason, "optional: " + reason));
        }
        if (changes.isEmpty()) {
            return null;
        }
        return new DefinitionUpdate("industry", definition.code(), definition.version(),
                nextMinor(definition.version()), reshape(definition, changes), changes);
    }

    /**
     * Spine learning for one definition: candidates are document types observed in
     * client sets from at least {@code minIndustriesForSpine} different industries
     * and not already expected; ONE judge call decides them all, and only a judged
     * project-type-independent type joins the spine.
     */
    private static DefinitionUpdate learnSpine(ChecklistDefinition spine,
            List<MandateObservation> mandates, ProjectTypeIndependenceJudge judge, Config config,
            List<ProjectTypeIndependenceJudge.Judgement> judgements) {
        Map<String, List<Long>> mandatesByType = new TreeMap<>();
        Map<String, Set<String>> industriesByType = new TreeMap<>();
        for (MandateObservation mandate : mandates) {
            if (!countryMatches(spine.country(), mandate.country())) {
                continue;
            }
            for (String documentType : mandate.documentTypes()) {
                mandatesByType.computeIfAbsent(documentType, k -> new ArrayList<>())
                        .add(mandate.mandateId());
                industriesByType.computeIfAbsent(documentType, k -> new TreeSet<>())
                        .addAll(mandate.industries());
            }
        }
        List<ProjectTypeIndependenceJudge.Candidate> candidates = new ArrayList<>();
        Set<String> candidateKeys = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : industriesByType.entrySet()) {
            String documentType = entry.getKey();
            if (entry.getValue().size() < config.minIndustriesForSpine()
                    || providesDocumentType(spine, documentType)
                    || keyInUse(spine, sanitizeKey(documentType))
                    || !candidateKeys.add(sanitizeKey(documentType))) {
                continue;
            }
            candidates.add(new ProjectTypeIndependenceJudge.Candidate(
                    documentType, List.copyOf(entry.getValue())));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        ProjectTypeIndependenceJudge.IndependenceResult judged = judge.judge(
                new ProjectTypeIndependenceJudge.IndependenceRequest(candidates));
        judgements.addAll(judged.judgements());

        List<ItemChange> changes = new ArrayList<>();
        for (ProjectTypeIndependenceJudge.Candidate candidate : candidates) {
            ProjectTypeIndependenceJudge.Judgement judgement =
                    judged.judgementFor(candidate.documentType());
            if (judgement == null || !judgement.independent()) {
                continue;
            }
            List<Long> sourceMandates = mandatesByType
                    .getOrDefault(candidate.documentType(), List.of()).stream()
                    .sorted()
                    .toList();
            String reason = "seen in the client document sets of mandates "
                    + mandateIds(sourceMandates) + " across industries "
                    + String.join(", ", candidate.industries())
                    + "; judged project-type independent: " + judgement.reason();
            changes.add(new ItemChange("promoted", sanitizeKey(candidate.documentType()),
                    candidate.documentType(), sourceMandates, reason,
                    "project-type independent: " + judgement.reason()));
        }
        if (changes.isEmpty()) {
            return null;
        }
        return new DefinitionUpdate("spine", spine.code(), spine.version(),
                nextMinor(spine.version()), reshape(spine, changes), changes);
    }

    /**
     * Builds the complete new definition: downgrades applied in place, added and
     * promoted items appended in key order — deterministic, so the written YAML is
     * reproducible.
     */
    private static ChecklistDefinition reshape(ChecklistDefinition definition,
            List<ItemChange> changes) {
        Map<String, ItemChange> optional = new LinkedHashMap<>();
        Map<String, ItemChange> added = new TreeMap<>();
        for (ItemChange change : changes) {
            if ("made_optional".equals(change.kind())) {
                optional.put(change.itemKey(), change);
            } else {
                added.put(change.itemKey(), change);
            }
        }
        List<ChecklistItemDef> items = new ArrayList<>();
        for (ChecklistItemDef item : definition.items()) {
            ItemChange downgrade = optional.get(item.key());
            if (downgrade == null) {
                items.add(item);
                continue;
            }
            items.add(new ChecklistItemDef(item.key(), item.label(), item.level(),
                    item.granularity(), item.entityRole(), item.documentType(), item.fieldKey(),
                    item.ontologyPath(), false, item.basisKind(), item.basisText(),
                    item.origin(), item.clientRequest(), downgrade.note()));
        }
        for (ItemChange change : added.values()) {
            items.add(new ChecklistItemDef(change.itemKey(), change.documentType(), "document",
                    "mandate", null, change.documentType(), null, null, true, "observed",
                    change.reason(),
                    "spine".equals(definition.scope()) ? "promoted" : "observed",
                    "Please share the {item} document.", change.note()));
        }
        return new ChecklistDefinition(definition.code(), nextMinor(definition.version()),
                definition.scope(), definition.country(), definition.industries(), items,
                definition.note());
    }

    /** Document type → the mandate ids that provided it, ascending, in type order. */
    private static Map<String, List<Long>> observedTypes(List<MandateObservation> group) {
        Map<String, List<Long>> providedBy = new TreeMap<>();
        for (MandateObservation mandate : group) {
            for (String documentType : mandate.documentTypes()) {
                providedBy.computeIfAbsent(documentType, k -> new ArrayList<>())
                        .add(mandate.mandateId());
            }
        }
        Map<String, List<Long>> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : providedBy.entrySet()) {
            sorted.put(entry.getKey(), entry.getValue().stream().sorted().toList());
        }
        return sorted;
    }

    private static boolean providesDocumentType(ChecklistDefinition definition,
            String documentType) {
        return definition.items().stream()
                .anyMatch(item -> documentType.equals(item.documentType()));
    }

    private static boolean keyInUse(ChecklistDefinition definition, String key) {
        return key.isEmpty() || definition.items().stream()
                .anyMatch(item -> key.equals(item.key()));
    }

    /** A mandate teaches a country's checklist only when its country matches it. */
    private static boolean countryMatches(String definitionCountry, String mandateCountry) {
        if (definitionCountry == null || definitionCountry.isBlank()) {
            return true;
        }
        return mandateCountry != null
                && definitionCountry.trim().equalsIgnoreCase(mandateCountry.trim());
    }

    /** The one fold rule, shared with scope matching: lowercase, spaces to underscores. */
    private static String foldIndustryKey(String industry) {
        return industry == null ? "" : ChecklistAssessor.normaliseIndustry(industry);
    }

    private static MandateObservation fold(MandateObservation mandate) {
        return new MandateObservation(mandate.mandateId(), mandate.country(),
                mandate.industries().stream()
                        .map(ChecklistLearner::foldIndustryKey)
                        .filter(key -> !key.isEmpty())
                        .distinct()
                        .toList(),
                mandate.documentTypes());
    }

    /** {@code approval.evacuation} → {@code approval_evacuation}. */
    static String sanitizeKey(String documentType) {
        if (documentType == null) {
            return "";
        }
        return documentType.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /** {@code 1.2.3} → {@code 1.3.0}: every learned change is a new minor version. */
    static String nextMinor(String version) {
        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        return major + "." + (minor + 1) + ".0";
    }

    private static String mandateIds(List<Long> ids) {
        List<String> text = new ArrayList<>();
        for (Long id : ids) {
            text.add(Long.toString(id));
        }
        return String.join(", ", text);
    }
}
