package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.BlockAnchor;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.db.AssessmentRepository.VersionRow;
import com.resurgent.tev.doc.db.EntityRepository;
import com.resurgent.tev.doc.llm.DocLlmClient;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The {@code assess_profile} entity step (issue #136): identifies the mandate's
 * entities — the borrower, its promoters and its group companies — from MOA/AOA,
 * the mandate letter, the DPR and similar documents through the LLM seam, merges
 * spelling variants into one entity with the #135 comparison rules, and persists
 * each entity with its role, aliases and page provenance. One transaction per run;
 * re-running replaces the previous entity set and fact tags and never duplicates
 * them.
 *
 * <p>Documents are asked only when they are plausible identity carriers: their
 * classified pack is an {@code identity.*} type or their text states an entity role
 * or an association document. The gate only decides which documents are paid for;
 * the model decides which entities they name.
 */
public final class EntityScan {

    /**
     * The deterministic cost gate: a document is asked when its text states one of
     * the mandate's roles or is an association document.
     */
    private static final Pattern IDENTITY_SIGNAL = Pattern.compile(
            "\\b(borrowers?|promoters?|group compan(?:y|ies)"
                    + "|memorandum of association|articles of association|moa|aoa)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Classified pack codes below this prefix are identity carriers. */
    private static final String IDENTITY_PACK_PREFIX = "identity.";

    /** One entity identification by the model, anchored to the document's text. */
    record Identification(
            long documentVersionId,
            String name,
            String role,
            String quote,
            Integer pageIndex,
            String reason) {}

    /**
     * Entity-scan counters for {@code run_stat} and the stage detail.
     *
     * @param entitiesTotal entities persisted for the mandate
     * @param entitiesBorrower entities with role {@code borrower}
     * @param entitiesPromoter entities with role {@code promoter}
     * @param entitiesGroupCompany entities with role {@code group_company}
     * @param documentsScanned document versions asked through the LLM seam
     */
    public record Stats(
            int entitiesTotal,
            int entitiesBorrower,
            int entitiesPromoter,
            int entitiesGroupCompany,
            int documentsScanned) {}

    private EntityScan() {}

    /**
     * Runs the entity scan over the mandate's documents.
     *
     * @param connection open workspace connection (transaction owned by the caller)
     * @param mandateId mandate scope
     * @param client document LLM seam, or null for the offline path
     * @param usageSink receives every LLM call's usage
     * @return the scan counters
     * @throws Exception on failure (the caller rolls back)
     */
    public static Stats run(Connection connection, long mandateId, DocLlmClient client,
            Consumer<DocLlmClient.Usage> usageSink) throws Exception {
        AssessmentRepository assessment = new AssessmentRepository(connection);
        EntityRepository entities = new EntityRepository(connection);

        entities.clearEntities(mandateId);

        List<Identification> identifications = new ArrayList<>();
        int scanned = 0;
        if (client != null) {
            for (VersionRow version : assessment.versionsForMandate(mandateId)) {
                List<PageText> pages = assessment.pageTexts(version.documentVersionId());
                if (pages.isEmpty() || !isIdentityCandidate(version, pages)) {
                    continue;
                }
                scanned++;
                identifications.addAll(LlmEntityScan.find(
                        client, pages, version.documentVersionId(), usageSink));
            }
        }

        List<MergedEntity> merged = merge(identifications);
        for (MergedEntity entity : merged) {
            persist(assessment, entities, mandateId, entity);
        }

        int borrower = 0;
        int promoter = 0;
        int groupCompany = 0;
        for (MergedEntity entity : merged) {
            switch (entity.role()) {
                case "borrower" -> borrower++;
                case "promoter" -> promoter++;
                case "group_company" -> groupCompany++;
                default -> { }
            }
        }
        return new Stats(merged.size(), borrower, promoter, groupCompany, scanned);
    }

    /**
     * Whether a document is a plausible identity carrier. Its classified pack is an
     * identity type, or its text states a mandate role or an association document.
     */
    static boolean isIdentityCandidate(VersionRow version, List<PageText> pages) {
        if (version.docTypeCode() != null
                && version.docTypeCode().startsWith(IDENTITY_PACK_PREFIX)) {
            return true;
        }
        for (PageText page : pages) {
            if (IDENTITY_SIGNAL.matcher(page.text()).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merges identifications into entities: names that are spellings of one name
     * ({@link EntityNames#sameName}, the #135 loose rules) are one entity — the
     * first identification sets the primary spelling, the role and the reason, later
     * spellings become aliases.
     */
    static List<MergedEntity> merge(List<Identification> identifications) {
        List<MutableEntity> merged = new ArrayList<>();
        for (Identification identification : identifications) {
            MutableEntity hit = null;
            for (MutableEntity candidate : merged) {
                if (EntityNames.sameName(identification.name(), candidate.name)) {
                    hit = candidate;
                    break;
                }
            }
            if (hit == null) {
                merged.add(new MutableEntity(identification));
            } else {
                hit.add(identification);
            }
        }
        return merged.stream().map(MutableEntity::freeze).toList();
    }

    /** Persists one merged entity: the row, its identifications' provenance, its aliases. */
    private static void persist(AssessmentRepository assessment, EntityRepository repo,
            long mandateId, MergedEntity entity) throws Exception {
        long entityId = repo.insertEntity(mandateId, entity.role(), entity.name(), entity.reason());
        Map<String, Long> firstProvenance = new LinkedHashMap<>();
        for (Identification identification : entity.identifications()) {
            Long provenanceId = insertProvenance(assessment, repo, entityId, identification);
            firstProvenance.putIfAbsent(identification.name(), provenanceId);
        }
        for (String alias : entity.aliases()) {
            repo.insertAlias(entityId, alias, firstProvenance.get(alias));
        }
    }

    /**
     * The provenance row of one identification: the block the quote anchors to when
     * the layout has it, else the page the text was found on, else page-less. The
     * page is never taken from the model.
     */
    private static Long insertProvenance(AssessmentRepository assessment, EntityRepository repo,
            long entityId, Identification identification) throws Exception {
        Optional<BlockAnchor> anchor =
                assessment.findBlockAnchor(identification.documentVersionId(),
                        identification.quote());
        if (anchor.isPresent()) {
            BlockAnchor block = anchor.get();
            return repo.insertProvenance(entityId, identification.documentVersionId(),
                    block.pageId(), block.blockId(), block.bboxX0(), block.bboxY0(),
                    block.bboxX1(), block.bboxY1(), identification.quote());
        }
        Long pageId = identification.pageIndex() == null ? null
                : repo.findPageId(identification.documentVersionId(),
                        identification.pageIndex());
        return repo.insertProvenance(entityId, identification.documentVersionId(), pageId,
                null, null, null, null, null, identification.quote());
    }

    /**
     * One entity under construction: the first identification sets the primary
     * spelling, role and reason; aliases accumulate in first-seen order.
     */
    private static final class MutableEntity {
        private final String name;
        private final String role;
        private String reason;
        private final Map<String, Boolean> aliases = new LinkedHashMap<>();
        private final List<Identification> identifications = new ArrayList<>();

        MutableEntity(Identification first) {
            this.name = first.name();
            this.role = first.role();
            this.reason = first.reason();
            add(first);
        }

        void add(Identification identification) {
            if (reason == null || reason.isBlank()) {
                reason = identification.reason();
            }
            aliases.putIfAbsent(identification.name(), Boolean.TRUE);
            identifications.add(identification);
        }

        MergedEntity freeze() {
            return new MergedEntity(name, role, reason, List.copyOf(aliases.keySet()),
                    List.copyOf(identifications));
        }
    }

    /**
     * One mandate entity: primary name, role, reason, alias spellings and every
     * identification behind it.
     */
    record MergedEntity(
            String name,
            String role,
            String reason,
            List<String> aliases,
            List<Identification> identifications) {}
}
