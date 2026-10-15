package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.compare.Comparisons;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.db.AssessmentRepository.VersionRow;
import com.resurgent.tev.doc.db.SiteRepository;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.parser.db.Timestamps;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Site step of the {@code assess_profile} stage (issue #137, spec #133): identifies the
 * mandate's sites from its documents (PPAs, quotations, DPR, PVsyst, …).
 *
 * <p>Site-name mentions are seeded from facts the packs already extracted (a quotation's
 * {@code project_name}, a DPR's land location, …). A mention merges into an existing site
 * through {@link Comparisons#site} — variants like Jaisingpura / Jaisingh Pura /
 * Aisinghpura match above {@link Comparisons#SITE_MATCH_SCORE} — and every match records
 * why on its alias row. Each document's own text is then scanned for known site spellings
 * so the PPA of a quotation's site lines up with it, without ever inventing a site from
 * free text. Re-running replaces the mandate's previous sites, aliases and fact tags.
 */
public final class SiteScan {

    /**
     * Scores at or above {@link Comparisons#SITE_MATCH_SCORE} merge without asking;
     * scores in [{@value SiteScan#SITE_AMBIGUOUS_FLOOR},
     * {@link Comparisons#SITE_MATCH_SCORE}) are confirmed through the LLM seam before a
     * merge; anything lower is a different site.
     */
    public static final double SITE_AMBIGUOUS_FLOOR = 0.5;

    /** Field keys whose value names the project site. */
    private static final Set<String> SITE_FIELD_KEYS = Set.of(
            "project_name", "site_name", "plant_name", "project_site", "site_location",
            "project_location", "location", "land_location");

    private static final Pattern TOKEN = Pattern.compile("[\\p{Alnum}]+");

    /** Site counts of one scan, for the stage metrics. */
    public record Result(int sites, int aliases) {}

    private SiteScan() {}

    /**
     * Identifies the mandate's sites and records them (one transaction owned by the caller).
     *
     * @param connection workspace connection (caller owns the transaction)
     * @param mandateId mandate scope
     * @param client document LLM seam for ambiguous scores, or null when offline
     * @param usageSink receives every LLM call's usage
     * @return the site/alias counts
     * @throws SQLException if any statement fails
     */
    public static Result scan(Connection connection, long mandateId, DocLlmClient client,
            Consumer<DocLlmClient.Usage> usageSink) throws SQLException {
        AssessmentRepository assessment = new AssessmentRepository(connection);
        SiteRepository repository = new SiteRepository(connection);
        String now = Timestamps.now();
        repository.clearMandateSites(mandateId);

        List<ResolvedSite> sites = new ArrayList<>();
        List<SiteRepository.FactSiteRow> facts = repository.factsForMandate(mandateId);
        Map<Long, Set<Long>> sitesByDocument = new LinkedHashMap<>();
        Map<Long, Long> siteByMentionFact = new HashMap<>();
        for (SiteRepository.FactSiteRow fact : facts) {
            if (!isSiteNameFact(fact)) {
                continue;
            }
            String mention = normalize(fact.valueText());
            if (mention.isEmpty()) {
                continue;
            }
            long siteId = resolveMention(mention, fact.documentVersionId(), mandateId,
                    repository, sites, now);
            siteByMentionFact.put(fact.factId(), siteId);
            sitesByDocument.computeIfAbsent(fact.documentVersionId(),
                    id -> new LinkedHashSet<>()).add(siteId);
        }

        // Each document's text may mention a known site under a different spelling
        // (the PPA "for the Jaisingh Pura solar project"). Only known sites are
        // matched — free text never creates a site.
        for (VersionRow version : assessment.versionsForMandate(mandateId)) {
            List<PageText> pages = assessment.pageTexts(version.documentVersionId());
            if (pages.isEmpty()) {
                continue;
            }
            for (ResolvedSite site : sites) {
                Window window = bestWindow(pages, site.displayNames);
                if (window == null) {
                    continue;
                }
                addAlias(repository, site, version.documentVersionId(), window.text(),
                        window.score(), reasonLoose(window.score()), now);
                sitesByDocument.computeIfAbsent(version.documentVersionId(),
                        id -> new LinkedHashSet<>()).add(site.siteId);
            }
        }

        // Tag every fact: a site-name fact with the site it names; any other fact with
        // the single site of its document; a mandate-wide document (no site mentioned)
        // leaves its facts without a site.
        for (SiteRepository.FactSiteRow fact : facts) {
            Long siteId = siteByMentionFact.get(fact.factId());
            if (siteId == null) {
                Set<Long> documentSites = sitesByDocument.get(fact.documentVersionId());
                if (documentSites != null && documentSites.size() == 1) {
                    siteId = documentSites.iterator().next();
                }
            }
            if (siteId != null) {
                repository.tagFact(fact.factId(), siteId);
            }
        }

        int aliases = 0;
        for (ResolvedSite site : sites) {
            aliases += site.aliases.size();
        }
        return new Result(sites.size(), aliases);
    }

    /**
     * Whether a fact names a project site: a known site field key, or a text value on a
     * {@code …Site…} / {@code …Location…} ontology path.
     */
    static boolean isSiteNameFact(SiteRepository.FactSiteRow fact) {
        if (fact.valueText() == null || fact.valueText().isBlank()) {
            return false;
        }
        String kind = fact.valueKind() == null ? "" : fact.valueKind().toLowerCase(Locale.ROOT);
        if (!kind.equals("text") && !kind.equals("enum")) {
            return false;
        }
        if (fact.fieldKey() != null
                && SITE_FIELD_KEYS.contains(fact.fieldKey().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (fact.ontologyPath() != null) {
            String path = fact.ontologyPath().toLowerCase(Locale.ROOT);
            return path.contains("site") || path.contains("location");
        }
        return false;
    }

    /**
     * Merges the mention into the best-scoring site, or creates a new site.
     *
     * @return the site id the mention belongs to
     */
    private static long resolveMention(String mention, long documentVersionId, long mandateId,
            SiteRepository repository, List<ResolvedSite> sites, String now) throws SQLException {
        ResolvedSite best = null;
        double bestScore = -1;
        for (ResolvedSite site : sites) {
            for (String name : site.displayNames) {
                double score = Comparisons.site(mention, name).score();
                if (score > bestScore) {
                    bestScore = score;
                    best = site;
                }
            }
        }
        if (best != null && bestScore >= Comparisons.SITE_MATCH_SCORE) {
            addAlias(repository, best, documentVersionId, mention, bestScore,
                    reasonLoose(bestScore), now);
            return best.siteId;
        }
        ResolvedSite created = new ResolvedSite(repository.insertSite(mandateId, mention, now));
        sites.add(created);
        addAlias(repository, created, documentVersionId, mention, 1.0, "first mention", now);
        return created.siteId;
    }

    /**
     * Adds one spelling mention for a site unless the same loose key is already recorded
     * for that site and document. A genuinely new spelling joins the site's forms, so
     * later comparisons can meet it.
     *
     * @return true when a row was written
     */
    private static boolean addAlias(SiteRepository repository, ResolvedSite site,
            long documentVersionId, String text, double score, String reason, String now)
            throws SQLException {
        for (AliasRecord alias : site.aliases) {
            if (alias.documentVersionId() == documentVersionId
                    && Comparisons.site(alias.text(), text).score() >= 1.0) {
                return false;
            }
        }
        repository.insertAlias(site.siteId, documentVersionId, text, score, reason, now);
        site.aliases.add(new AliasRecord(documentVersionId, text));
        boolean knownForm = false;
        for (String name : site.displayNames) {
            if (Comparisons.site(name, text).score() >= 1.0) {
                knownForm = true;
                break;
            }
        }
        if (!knownForm) {
            site.displayNames.add(text);
        }
        return true;
    }

    /** The tightest, highest-scoring window of a document that matches a site spelling. */
    private static Window bestWindow(List<PageText> pages, List<String> names) {
        int maxSize = 1;
        for (String name : names) {
            maxSize = Math.max(maxSize, tokenCount(name) + 1);
        }
        Window best = null;
        for (PageText page : pages) {
            String text = page.text();
            List<int[]> tokens = tokens(text);
            for (int i = 0; i < tokens.size(); i++) {
                for (int size = 1; size <= maxSize && i + size <= tokens.size(); size++) {
                    String window = text.substring(tokens.get(i)[0],
                            tokens.get(i + size - 1)[1]).trim();
                    double score = bestScore(window, names);
                    if (score < Comparisons.SITE_MATCH_SCORE) {
                        continue;
                    }
                    if (best == null || score > best.score()
                            || (score == best.score() && size < best.size())) {
                        best = new Window(window, score, size);
                    }
                }
            }
        }
        return best;
    }

    private static double bestScore(String window, List<String> names) {
        double best = 0.0;
        for (String name : names) {
            best = Math.max(best, Comparisons.site(window, name).score());
        }
        return best;
    }

    /** Alnum tokens of a text with their source offsets, preserving the stated spelling. */
    private static List<int[]> tokens(String text) {
        List<int[]> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(new int[] {matcher.start(), matcher.end()});
        }
        return tokens;
    }

    private static int tokenCount(String text) {
        return tokens(text).size();
    }

    private static String reasonLoose(double score) {
        if (score >= 1.0) {
            return "same loose key";
        }
        return String.format(Locale.ROOT, "loose score %.2f (>= %.2f)",
                score, Comparisons.SITE_MATCH_SCORE);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    /** A site as the scan builds it up: canonical id plus the spellings met so far. */
    private static final class ResolvedSite {
        final long siteId;
        final List<String> displayNames = new ArrayList<>();
        final List<AliasRecord> aliases = new ArrayList<>();

        ResolvedSite(long siteId) {
            this.siteId = siteId;
        }
    }

    /** One recorded spelling of a site, with the document it was seen in. */
    private record AliasRecord(long documentVersionId, String text) {}

    /** A matching window of document text: the stated spelling and its loose score. */
    private record Window(String text, double score, int size) {}
}
