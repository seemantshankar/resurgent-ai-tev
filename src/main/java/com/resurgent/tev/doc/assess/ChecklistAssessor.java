package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.checklist.ChecklistDefinition;
import com.resurgent.tev.doc.checklist.ChecklistItemDef;
import com.resurgent.tev.doc.db.ChecklistRepository;
import com.resurgent.tev.doc.db.ChecklistRepository.DocScopeRow;
import com.resurgent.tev.doc.db.ChecklistRepository.FactEvidenceRow;
import com.resurgent.tev.doc.query.MandateQa;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The deterministic M3-10 checklist engine (issue #143). It compares the expected
 * items of the in-scope checklist definitions (versioned data — never code) against
 * the persisted classifications, facts and entities of a mandate and decides each
 * item's status this ticket ships:
 *
 * <ul>
 *   <li>{@code provided}: resolved evidence above {@value #MIN_EVIDENCE_CONFIDENCE}
 *       confidence with a cited page;</li>
 *   <li>{@code unclear}: evidence is present but low-confidence, ambiguous or
 *       unanchored — never counted as provided;</li>
 *   <li>{@code not_provided}: nothing usable was found; the wording to ask the
 *       client is carried on the result.</li>
 * </ul>
 *
 * <p>The engine never guesses: it reads only persisted state, needs no LLM and is
 * reproducible for the same workspace. Entity-granularity items expand once per
 * matching entity (a fact of the document tagged to that entity owns it for the
 * entity); a mandate with no such entity still gets one row with no entity, so an
 * expected item never silently vanishes. The effective checklist is the mandate's
 * country spine plus each of its industries (M3-11); an item whose data point
 * carries a conflict no rule or AI judgement settled becomes Conflicting through
 * {@link #applyOpenConflicts} (M3-12). Not applicable is decided by the model in a
 * separate pass, never marked by hand.
 */
public final class ChecklistAssessor {

    /** Evidence at or above this confidence is Provided; below it the item is Unclear. */
    public static final double MIN_EVIDENCE_CONFIDENCE = 0.7;

    public static final String STATUS_PROVIDED = "provided";

    public static final String STATUS_UNCLEAR = "unclear";

    public static final String STATUS_NOT_PROVIDED = "not_provided";

    /** An open conflict on the item's data point: no value is chosen (M3-12). */
    public static final String STATUS_CONFLICTING = "conflicting";

    /** Decided by the model with a reason and page (M3-11) — never marked by hand. */
    public static final String STATUS_NOT_APPLICABLE = "not_applicable";

    /** Everything the engine reads — all persisted by the earlier stages. */
    public record Inputs(
            List<MandateQa.EntityRow> entities,
            List<MandateQa.SiteRow> sites,
            List<DocScopeRow> documents,
            List<FactEvidenceRow> facts,
            List<MandateQa.IndustryRow> industries,
            MandateQa.CountryRow country) {}

    /**
     * One assessed (item, scope) standing, ready for persistence.
     *
     * @param item the definition item this result is for
     * @param checklistCode definition code the item came from
     * @param checklistVersion definition version the item came from
     * @param definitionScope {@code spine} | {@code industry}
     * @param country country the definition applies to
     * @param industry industry of an industry definition, or null for a spine
     * @param note the item's own note (e.g. why it became optional — M3-15) when
     *     it carries one, else the definition's label (e.g.
     *     {@code new industry: first mandate}), or null
     * @param entityId entity the result is for, or null
     * @param entityName that entity's name, or null
     * @param siteId site the result is for, or null
     * @param siteName that site's name, or null
     * @param status {@code provided} | {@code unclear} | {@code not_provided} |
     *     {@code not_applicable} | {@code conflicting}
     * @param reason why the status was set (never blank)
     * @param documentVersionId evidence or owning document version, or null
     * @param documentFileName that version's file name, or null
     * @param pageIndex cited page, present for {@code provided}
     * @param confidence evidence confidence, or null
     * @param evidenceVerbatim the resolved quote the result relies on, or null
     * @param clientRequest filled ask-the-client wording, set when {@code not_provided}
     */
    public record Result(
            ChecklistItemDef item,
            String checklistCode,
            String checklistVersion,
            String definitionScope,
            String country,
            String industry,
            String note,
            Long entityId,
            String entityName,
            Long siteId,
            String siteName,
            String status,
            String reason,
            Long documentVersionId,
            String documentFileName,
            Integer pageIndex,
            Double confidence,
            String evidenceVerbatim,
            String clientRequest) {}

    /** The one scope an item is assessed for: none (mandate), an entity or a site. */
    private record Scope(
            Long entityId,
            String entityName,
            Long siteId,
            String siteName,
            String entityRole,
            boolean fallback) {}

    /** An effective item together with the definition it came from. */
    private record Effective(ChecklistItemDef item, ChecklistDefinition definition) {}

    private ChecklistAssessor() {}

    /**
     * Plans every result for the mandate: definitions in scope, overrides applied,
     * items expanded per scope and matched against the persisted state.
     *
     * @param inputs mandate state loaded from the workspace
     * @param catalog every loaded definition (seed spine plus the store)
     * @param overrides the analyst's add/remove overrides, oldest first
     * @return one result per (item, scope), in definition order
     */
    public static List<Result> plan(Inputs inputs, Collection<ChecklistDefinition> catalog,
            List<MandateQa.ChecklistOverrideRow> overrides) {
        Map<Long, List<FactEvidenceRow>> factsByVersion = inputs.facts().stream()
                .collect(Collectors.groupingBy(FactEvidenceRow::documentVersionId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<String, List<DocScopeRow>> versionsByType = new LinkedHashMap<>();
        for (DocScopeRow document : inputs.documents()) {
            if (document.docTypeCode() != null) {
                for (String code : com.resurgent.tev.doc.pack.PackCodes.equivalents(
                        document.docTypeCode())) {
                    versionsByType.computeIfAbsent(code, key -> new ArrayList<>()).add(document);
                }
            }
        }

        List<Result> results = new ArrayList<>();
        for (Effective effective : effectiveItems(catalog, overrides, inputs)) {
            for (Scope scope : scopes(effective.item(), inputs)) {
                results.add(assess(effective, scope, factsByVersion, versionsByType));
            }
        }
        return List.copyOf(results);
    }

    /**
     * Applies the mandate's open conflicts to the planned results (M3-12, ADR 0026
     * tier 3): a data point left open — no precedence rule covered it and AI
     * judgement did not settle it — makes its checklist item Conflicting. A
     * mandate-granularity item is conflicted whenever its data point is, in any
     * scope; an entity/site item only when the conflict was formed in exactly that
     * scope. No value is chosen. Rule- and AI-settled conflicts never change a
     * status: their settlement is shown on the conflict row itself, and a revision
     * ({@code versions-differ}) is not a conflict.
     *
     * @param plan the planned results
     * @param openConflicts the mandate's conflicts with settlement {@code open}
     * @return the plan, with the affected results Conflicting
     */
    public static List<Result> applyOpenConflicts(List<Result> plan,
            List<MandateQa.ConflictRow> openConflicts) {
        if (openConflicts.isEmpty()) {
            return plan;
        }
        List<Result> applied = new ArrayList<>(plan.size());
        for (Result result : plan) {
            MandateQa.ConflictRow conflict = openConflictFor(result, openConflicts);
            applied.add(conflict == null ? result : conflicting(result, conflict));
        }
        return List.copyOf(applied);
    }

    /** The open conflict on the item's data point within the result's scope, or null. */
    private static MandateQa.ConflictRow openConflictFor(Result result,
            List<MandateQa.ConflictRow> openConflicts) {
        ChecklistItemDef item = result.item();
        for (MandateQa.ConflictRow conflict : openConflicts) {
            if (!inScope(result, conflict)) {
                continue;
            }
            if (item.fieldKey() != null && item.fieldKey().equals(conflict.fieldKey())) {
                return conflict;
            }
            if (item.ontologyPath() != null
                    && item.ontologyPath().equals(conflict.ontologyPath())) {
                return conflict;
            }
        }
        return null;
    }

    /**
     * Whether the conflict falls within the result's scope: a mandate-granularity
     * result covers every scope of the data point (the comparison may have been
     * made within a site or entity), an entity/site result only the conflict
     * formed in exactly that scope.
     */
    private static boolean inScope(Result result, MandateQa.ConflictRow conflict) {
        if (result.entityId() == null && result.siteId() == null) {
            return true;
        }
        return Objects.equals(result.entityId(), conflict.entityId())
                && Objects.equals(result.siteId(), conflict.siteId());
    }

    /** The same result, Conflicting: the conflict row is the record of both values. */
    private static Result conflicting(Result result, MandateQa.ConflictRow conflict) {
        return new Result(result.item(), result.checklistCode(), result.checklistVersion(),
                result.definitionScope(), result.country(), result.industry(),
                result.note(),
                result.entityId(), result.entityName(), result.siteId(), result.siteName(),
                STATUS_CONFLICTING,
                "conflicting " + conflict.dataPoint() + " across documents: "
                        + conflict.left().value() + " vs " + conflict.right().value()
                        + " — no precedence rule covered the disagreement and AI judgement"
                        + " did not settle it",
                null, null, null, null, null, null);
    }

    /**
     * The items to assess: the in-scope definitions minus removed keys, plus any key
     * the analyst added (the latest override per item key wins; an add of an unknown
     * key is ignored with a note — the CLI already validates it and nothing ever
     * blocks the assessment).
     */
    static List<Effective> effectiveItems(Collection<ChecklistDefinition> catalog,
            List<MandateQa.ChecklistOverrideRow> overrides, Inputs inputs) {
        Map<String, String> latestAction = new LinkedHashMap<>();
        for (MandateQa.ChecklistOverrideRow override : overrides) {
            latestAction.put(override.itemKey(), override.action());
        }
        List<Effective> effective = new ArrayList<>();
        Set<String> included = new HashSet<>();
        for (ChecklistDefinition definition : inScope(catalog, inputs)) {
            for (ChecklistItemDef item : definition.items()) {
                if (!"remove".equals(latestAction.get(item.key())) && included.add(item.key())) {
                    effective.add(new Effective(item, definition));
                }
            }
        }
        for (Map.Entry<String, String> action : latestAction.entrySet()) {
            if (!"add".equals(action.getValue()) || included.contains(action.getKey())) {
                continue;
            }
            Effective found = findInCatalog(catalog, action.getKey());
            if (found != null) {
                effective.add(found);
                included.add(action.getKey());
            } else {
                System.err.println("[doc-assess] checklist add for unknown item key '"
                        + action.getKey() + "' ignored");
            }
        }
        return effective;
    }

    /**
     * The definitions in scope (M3-11): each definition's country must match the
     * mandate's country (an unknown country on either side matches everything, so
     * mandates without a recorded country keep their spine), and an industry
     * definition additionally needs one of its industries to be one of the
     * mandate's industry tags. The spine of a matching country is always on top —
     * the mandate's effective checklist is the spine plus each of its industries,
     * and items outside its industries are never asked.
     */
    static List<ChecklistDefinition> inScope(Collection<ChecklistDefinition> catalog,
            Inputs inputs) {
        String mandateCountry = countryCode(inputs.country());
        Set<String> mandateIndustries = inputs.industries().stream()
                .map(MandateQa.IndustryRow::industry)
                .filter(industry -> industry != null && !industry.isBlank())
                .map(ChecklistAssessor::normaliseIndustry)
                .collect(Collectors.toSet());
        List<ChecklistDefinition> inScope = new ArrayList<>();
        for (ChecklistDefinition definition : catalog) {
            if (!countryMatches(definition, mandateCountry)) {
                continue;
            }
            if (definition.isSpine()) {
                inScope.add(definition);
            } else if (definition.industries().stream()
                    .map(ChecklistAssessor::normaliseIndustry)
                    .anyMatch(mandateIndustries::contains)) {
                inScope.add(definition);
            }
        }
        return inScope;
    }

    /** Whether a definition's country matches the mandate's: unknown on either side matches. */
    private static boolean countryMatches(ChecklistDefinition definition, String mandateCountry) {
        String definitionCountry = definition.country();
        if (definitionCountry == null || definitionCountry.isBlank() || mandateCountry == null) {
            return true;
        }
        return definitionCountry.trim().equalsIgnoreCase(mandateCountry);
    }

    /**
     * The ISO-3166 alpha-2 code of the mandate's country, or null when no country is
     * recorded: {@code India} (its canonical scan name) maps to {@code IN}, a two
     * letter name is upper-cased, anything else stays unknown and matches all.
     */
    public static String countryCode(MandateQa.CountryRow country) {
        if (country == null || country.country() == null) {
            return null;
        }
        String name = country.country().trim();
        if (name.isEmpty()) {
            return null;
        }
        if ("india".equalsIgnoreCase(name)) {
            return "IN";
        }
        return name.length() == 2 ? name.toUpperCase(Locale.ROOT) : null;
    }

    /**
     * Industry labels compare case-insensitively with spaces folded to underscores —
     * the one fold rule for scope matching and for learning (M3-15) alike.
     */
    public static String normaliseIndustry(String industry) {
        return industry.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static Effective findInCatalog(Collection<ChecklistDefinition> catalog, String key) {
        for (ChecklistDefinition definition : catalog) {
            for (ChecklistItemDef item : definition.items()) {
                if (item.key().equals(key)) {
                    return new Effective(item, definition);
                }
            }
        }
        return null;
    }

    /** The scopes one item is assessed once per: mandate, each matching entity, each site. */
    static List<Scope> scopes(ChecklistItemDef item, Inputs inputs) {
        switch (item.granularity()) {
            case "entity" -> {
                List<Scope> scopes = new ArrayList<>();
                for (MandateQa.EntityRow entity : inputs.entities()) {
                    if (item.entityRole() == null || item.entityRole().equals(entity.role())) {
                        scopes.add(new Scope(entity.entityId(), entity.name(), null, null,
                                entity.role(), false));
                    }
                }
                if (scopes.isEmpty()) {
                    // No entity of the role yet: keep one row without an entity so the
                    // item is reported (and asked for) instead of silently vanishing.
                    scopes.add(new Scope(null, null, null, null, item.entityRole(), true));
                }
                return scopes;
            }
            case "site" -> {
                List<Scope> scopes = new ArrayList<>();
                for (MandateQa.SiteRow site : inputs.sites()) {
                    scopes.add(new Scope(null, null, site.siteId(), site.siteName(), null, false));
                }
                if (scopes.isEmpty()) {
                    scopes.add(new Scope(null, null, null, null, null, true));
                }
                return scopes;
            }
            default -> {
                return List.of(new Scope(null, null, null, null, null, false));
            }
        }
    }

    /**
     * Decides one item's status for one scope: the expected document version(s) the
     * scope owns are matched by document type (an entity scope also needs a fact of
     * the version tagged to the entity), data points additionally by field key or
     * ontology path; the best evidence decides provided vs unclear, absence is
     * not_provided with the ask-the-client wording.
     */
    static Result assess(Effective effective, Scope scope,
            Map<Long, List<FactEvidenceRow>> factsByVersion,
            Map<String, List<DocScopeRow>> versionsByType) {
        ChecklistItemDef item = effective.item();
        List<DocScopeRow> candidates = new ArrayList<>();
        for (DocScopeRow document : versionsByType.getOrDefault(item.documentType(), List.of())) {
            if (ownedBy(document, scope,
                    factsByVersion.getOrDefault(document.documentVersionId(), List.of()))) {
                candidates.add(document);
            }
        }
        return item.isDataPointLevel()
                ? assessDataPoint(effective, scope, candidates, factsByVersion)
                : assessDocument(effective, scope, candidates, factsByVersion);
    }

    private static Result assessDocument(Effective effective, Scope scope,
            List<DocScopeRow> candidates, Map<Long, List<FactEvidenceRow>> factsByVersion) {
        ChecklistItemDef item = effective.item();
        if (candidates.isEmpty()) {
            return notProvided(effective, scope, null, null,
                    "no " + item.documentType() + " document is provided" + forScope(scope));
        }
        FactEvidenceRow best = best(scopedFacts(candidates, scope, factsByVersion));
        DocScopeRow owning = candidates.get(0);
        if (best == null) {
            if (owning.memberPageIndex() != null) {
                String cite = "the " + item.documentType() + " document is provided"
                        + forScope(scope) + " (page " + owning.memberPageIndex() + ")";
                return result(effective, scope, STATUS_PROVIDED, cite,
                        owning.documentVersionId(), owning.fileName(),
                        owning.memberPageIndex(), 1.0, cite, null);
            }
            return unclear(effective, scope, owning, null, null,
                    "the " + item.documentType() + " document is present" + forScope(scope)
                            + " but no readable evidence was extracted from it");
        }
        if (isProvided(best)) {
            return provided(effective, scope, best,
                    "the " + item.documentType() + " document is provided" + forScope(scope));
        }
        return unclear(effective, scope, version(candidates, best), best.pageIndex(),
                best.confidence(),
                "the " + item.documentType() + " document is present" + forScope(scope)
                        + " but " + whyUnclear(best));
    }

    private static Result assessDataPoint(Effective effective, Scope scope,
            List<DocScopeRow> candidates, Map<Long, List<FactEvidenceRow>> factsByVersion) {
        ChecklistItemDef item = effective.item();
        List<FactEvidenceRow> matching = new ArrayList<>();
        for (DocScopeRow candidate : candidates) {
            for (FactEvidenceRow fact : factsByVersion.getOrDefault(
                    candidate.documentVersionId(), List.of())) {
                if (scopeMatch(fact, scope) && matchesDataPoint(item, fact)) {
                    matching.add(fact);
                }
            }
        }
        if (candidates.isEmpty()) {
            return notProvided(effective, scope, null, null,
                    "no " + item.documentType() + " document is provided" + forScope(scope)
                            + ", so the expected data point " + item.label()
                            + " is not available");
        }
        if (matching.isEmpty()) {
            DocScopeRow owning = candidates.get(0);
            return notProvided(effective, scope, owning.documentVersionId(),
                    owning.fileName(),
                    "the " + item.documentType() + " document is present" + forScope(scope)
                            + " but does not state the expected data point " + item.label()
                            + fieldKeySuffix(item));
        }
        FactEvidenceRow best = best(matching);
        if (isProvided(best)) {
            return provided(effective, scope, best,
                    "the " + item.label() + " data point is provided" + forScope(scope));
        }
        return unclear(effective, scope, version(candidates, best), best.pageIndex(),
                best.confidence(),
                "the " + item.label() + " data point is present" + forScope(scope)
                        + " but " + whyUnclear(best));
    }

    /** Whether the scope owns the version: entity/site scope needs a fact tied to it. */
    private static boolean ownedBy(DocScopeRow document, Scope scope,
            List<FactEvidenceRow> versionFacts) {
        if (scope.entityId() != null) {
            return versionFacts.stream()
                    .anyMatch(fact -> scope.entityId().equals(fact.entityId()));
        }
        if (scope.siteId() != null) {
            return versionFacts.stream().anyMatch(fact -> scope.siteId().equals(fact.siteId()));
        }
        return true;
    }

    private static List<FactEvidenceRow> scopedFacts(List<DocScopeRow> candidates, Scope scope,
            Map<Long, List<FactEvidenceRow>> factsByVersion) {
        List<FactEvidenceRow> scoped = new ArrayList<>();
        for (DocScopeRow candidate : candidates) {
            for (FactEvidenceRow fact : factsByVersion.getOrDefault(
                    candidate.documentVersionId(), List.of())) {
                if (scopeMatch(fact, scope)) {
                    scoped.add(fact);
                }
            }
        }
        return scoped;
    }

    private static boolean scopeMatch(FactEvidenceRow fact, Scope scope) {
        if (scope.entityId() != null) {
            return scope.entityId().equals(fact.entityId());
        }
        if (scope.siteId() != null) {
            return scope.siteId().equals(fact.siteId());
        }
        return true;
    }

    private static boolean matchesDataPoint(ChecklistItemDef item, FactEvidenceRow fact) {
        if (item.fieldKey() != null && item.fieldKey().equals(fact.fieldKey())) {
            return true;
        }
        return item.ontologyPath() != null && item.ontologyPath().equals(fact.ontologyPath());
    }

    /**
     * Provided requires resolved evidence above threshold and a cited page — the
     * ticket's bar. Everything weaker is Unclear, which is never counted as provided.
     */
    private static boolean isProvided(FactEvidenceRow fact) {
        return fact.confidence() != null
                && fact.confidence() >= MIN_EVIDENCE_CONFIDENCE
                && "resolved".equals(fact.resolution())
                && fact.pageIndex() != null;
    }

    /** Why evidence below the provided bar is unclear, phrased for the analyst. */
    private static String whyUnclear(FactEvidenceRow fact) {
        if (fact.confidence() == null || fact.confidence() < MIN_EVIDENCE_CONFIDENCE) {
            return "its evidence confidence " + confidenceText(fact.confidence())
                    + " is below the " + thresholdText() + " threshold";
        }
        if (!"resolved".equals(fact.resolution())) {
            return "its evidence is " + (fact.resolution() == null
                    ? "not resolved" : fact.resolution()) + " in the document layout";
        }
        return "its evidence could not be anchored to a page";
    }

    private static Result provided(Effective effective, Scope scope, FactEvidenceRow best,
            String what) {
        return result(effective, scope, STATUS_PROVIDED,
                what + " (page " + best.pageIndex() + ", evidence confidence "
                        + confidenceText(best.confidence()) + ")",
                best.documentVersionId(), null, best.pageIndex(), best.confidence(),
                best.evidenceVerbatim(), null);
    }

    private static Result unclear(Effective effective, Scope scope, DocScopeRow owning,
            Integer pageIndex, Double confidence, String reason) {
        return result(effective, scope, STATUS_UNCLEAR, reason,
                owning == null ? null : owning.documentVersionId(),
                owning == null ? null : owning.fileName(),
                pageIndex, confidence, null, null);
    }

    private static Result notProvided(Effective effective, Scope scope, Long documentVersionId,
            String documentFileName, String reason) {
        return result(effective, scope, STATUS_NOT_PROVIDED, reason,
                documentVersionId, documentFileName, null, null, null,
                clientWording(effective.item(), scope));
    }

    private static Result result(Effective effective, Scope scope, String status, String reason,
            Long documentVersionId, String documentFileName, Integer pageIndex, Double confidence,
            String evidenceVerbatim, String clientRequest) {
        ChecklistItemDef item = effective.item();
        ChecklistDefinition definition = effective.definition();
        return new Result(item, definition.code(), definition.version(),
                definition.scope(), definition.country(),
                definition.isSpine() ? null : definition.industries().get(0),
                item.note() != null ? item.note() : definition.note(),
                scope.entityId(), scope.entityName(), scope.siteId(), scope.siteName(),
                status, reason, documentVersionId, documentFileName, pageIndex, confidence,
                evidenceVerbatim, clientRequest);
    }

    /** The version row a best fact came from (candidates are the only versions it may cite). */
    private static DocScopeRow version(List<DocScopeRow> candidates, FactEvidenceRow fact) {
        for (DocScopeRow candidate : candidates) {
            if (candidate.documentVersionId() == fact.documentVersionId()) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    /** The best evidence: highest confidence, then paged, then resolved, then earliest. */
    private static FactEvidenceRow best(List<FactEvidenceRow> facts) {
        FactEvidenceRow best = null;
        for (FactEvidenceRow fact : facts) {
            if (best == null || better(fact, best)) {
                best = fact;
            }
        }
        return best;
    }

    private static boolean better(FactEvidenceRow candidate, FactEvidenceRow current) {
        boolean candidateResolved = "resolved".equals(candidate.resolution());
        boolean currentResolved = "resolved".equals(current.resolution());
        if (candidateResolved != currentResolved) {
            return candidateResolved;
        }
        double candidateConfidence = candidate.confidence() == null ? -1 : candidate.confidence();
        double currentConfidence = current.confidence() == null ? -1 : current.confidence();
        if (candidateConfidence != currentConfidence) {
            return candidateConfidence > currentConfidence;
        }
        if ((candidate.pageIndex() != null) != (current.pageIndex() != null)) {
            return candidate.pageIndex() != null;
        }
        return false;
    }

    /**
     * The filled ask-the-client wording: {@code {entity}}, {@code {site}} and
     * {@code {item}} substituted per scope.
     */
    static String clientWording(ChecklistItemDef item, Scope scope) {
        String entity = scope.entityName() != null ? scope.entityName()
                : scope.entityRole() != null ? "the " + scope.entityRole() : "the entity";
        String site = scope.siteName() != null ? "site " + scope.siteName()
                : scope.fallback() ? "the site" : "";
        return item.clientRequest()
                .replace("{entity}", entity)
                .replace("{site}", site)
                .replace("{item}", item.label());
    }

    private static String forScope(Scope scope) {
        if (scope.entityId() != null) {
            return " for " + scope.entityName();
        }
        if (scope.siteId() != null) {
            return " for site " + scope.siteName();
        }
        if (scope.fallback()) {
            return scope.entityRole() != null
                    ? " (no " + scope.entityRole() + " entity is identified yet)"
                    : " (no site is identified yet)";
        }
        return "";
    }

    private static String fieldKeySuffix(ChecklistItemDef item) {
        if (item.fieldKey() != null) {
            return " (field " + item.fieldKey() + ")";
        }
        return item.ontologyPath() != null ? " (path " + item.ontologyPath() + ")" : "";
    }

    private static String confidenceText(Double confidence) {
        return confidence == null ? "unknown"
                : String.format(Locale.ROOT, "%.2f", confidence);
    }

    private static String thresholdText() {
        return String.format(Locale.ROOT, "%.2f", MIN_EVIDENCE_CONFIDENCE);
    }
}
