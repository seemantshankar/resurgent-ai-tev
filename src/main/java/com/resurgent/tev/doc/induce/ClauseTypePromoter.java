package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.pack.ClauseSection;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackClauseType;
import com.resurgent.tev.doc.pack.PackField;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Auto-promotion of recurring open clause types (M3-09, spec "Auto-promotion"). Open
 * proposals are grouped by {@link ClauseLabelNormalizer}; a group whose clauses appeared
 * in at least the M2 consensus number of documents is added to its document type's pack
 * as a new version with the source labels as aliases — after passing the same
 * {@link PackQualityGates} as field induction. There is no human approval step:
 * architecture §9 Pass C is superseded. A group that is a one-off, names only entity
 * values, or fails a gate stays open and is reported with its reason.
 */
public final class ClauseTypePromoter {

    /** One document a proposal's clauses were read from. */
    public record SourceDocument(String id, String logicalName, String fileName) {}

    /** One open proposed clause type with the documents that evidence it. */
    public record OpenProposal(
            String code,
            String label,
            String parentFamily,
            List<SourceDocument> documents) {

        public OpenProposal {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }

    /** The open proposals of one document type — the promotion unit (one pack version). */
    public record Candidate(String docTypeCode, List<OpenProposal> proposals) {

        public Candidate {
            proposals = proposals == null ? List.of() : List.copyOf(proposals);
        }
    }

    /** Input to {@link #promote}. */
    public record PromotionRequest(
            long mandateId,
            Candidate candidate,
            ClauseLabelNormalizer normalizer,
            PackStore store,
            Map<String, DocTypePack> basePacksByCode,
            int consensusN,
            double minRequiredCoverage) {

        public PromotionRequest {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(normalizer, "normalizer");
            Objects.requireNonNull(store, "store");
            basePacksByCode = basePacksByCode == null ? Map.of() : Map.copyOf(basePacksByCode);
            consensusN = consensusN < 1 ? Generalizer.DEFAULT_CONSENSUS_N : consensusN;
            minRequiredCoverage = minRequiredCoverage <= 0
                    ? PackQualityGates.DEFAULT_MIN_REQUIRED_COVERAGE
                    : minRequiredCoverage;
        }
    }

    /** One clause type promoted into a new pack version. */
    public record PromotedType(
            String packCode,
            String packVersion,
            String key,
            String label,
            String parentFamily,
            List<String> aliases,
            List<String> memberCodes,
            List<String> sourceDocumentIds,
            List<String> gateMessages) {

        public PromotedType {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            memberCodes = memberCodes == null ? List.of() : List.copyOf(memberCodes);
            sourceDocumentIds =
                    sourceDocumentIds == null ? List.of() : List.copyOf(sourceDocumentIds);
            gateMessages = gateMessages == null ? List.of() : List.copyOf(gateMessages);
        }
    }

    /** One group that was not promoted, with the reason (it stays open). */
    public record SkippedGroup(
            String key,
            String label,
            List<String> memberCodes,
            List<String> sourceDocumentIds,
            String reason) {

        public SkippedGroup {
            memberCodes = memberCodes == null ? List.of() : List.copyOf(memberCodes);
            sourceDocumentIds =
                    sourceDocumentIds == null ? List.of() : List.copyOf(sourceDocumentIds);
        }
    }

    /** Outcome of promoting one document type's open proposals. */
    public record PromotionResult(
            String docTypeCode,
            String packVersion,
            List<PromotedType> promoted,
            List<SkippedGroup> skipped,
            int proposalsRead) {

        public PromotionResult {
            promoted = promoted == null ? List.of() : List.copyOf(promoted);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }
    }

    /**
     * Groups the candidate's proposals, applies the consensus and quality gates, and —
     * unless a gate fails — publishes one new pack version carrying every promoted group.
     *
     * @param request promotion request (required)
     * @return promoted types and the groups kept open, each with its reason
     * @throws IOException when the pack store cannot publish
     */
    public PromotionResult promote(PromotionRequest request) throws IOException {
        Candidate candidate = request.candidate();
        if (candidate.proposals().isEmpty()) {
            return new PromotionResult(candidate.docTypeCode(), null, List.of(), List.of(), 0);
        }
        List<SkippedGroup> skipped = new ArrayList<>();
        DocTypePack base = resolveBasePack(request);
        if (base == null || !base.declaresClauses()) {
            String reason = base == null
                    ? "no pack version for document type " + candidate.docTypeCode()
                    : "pack " + base.identity() + " declares no clauses";
            for (OpenProposal proposal : candidate.proposals()) {
                skipped.add(new SkippedGroup(proposal.code(), proposal.label(),
                        List.of(proposal.code()), documentIds(proposal.documents()), reason));
            }
            return new PromotionResult(candidate.docTypeCode(), null, List.of(), skipped,
                    candidate.proposals().size());
        }

        ClauseLabelNormalizer.NormalizationResult normalized = request.normalizer().normalize(
                new ClauseLabelNormalizer.NormalizationRequest(candidate.proposals().stream()
                        .map(p -> new ClauseLabelNormalizer.ProposedLabel(
                                p.code(), p.label(), p.parentFamily()))
                        .toList()));

        Map<String, OpenProposal> byCode = new LinkedHashMap<>();
        for (OpenProposal proposal : candidate.proposals()) {
            byCode.putIfAbsent(proposal.code(), proposal);
        }

        List<Eligible> eligible = new ArrayList<>();
        for (ClauseLabelNormalizer.LabelGroup group : normalized.groups()) {
            List<OpenProposal> members = new ArrayList<>();
            List<String> memberCodes = new ArrayList<>();
            for (String code : group.memberCodes()) {
                OpenProposal member = byCode.get(code);
                if (member != null && !memberCodes.contains(code)) {
                    members.add(member);
                    memberCodes.add(code);
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            List<String> documentIds = distinctDocumentIds(members);
            String key = sanitizeKey(group.key());
            PackClauseType existing = base.clauseTypes().stream()
                    .filter(t -> t.key().equals(key))
                    .findFirst()
                    .orElse(null);
            if (key.isEmpty() || existing != null) {
                skipped.add(new SkippedGroup(key.isEmpty() ? group.key() : key, group.label(),
                        memberCodes, documentIds,
                        existing != null
                                ? "already declared by " + base.identity() + " as '" + key + "'"
                                : "label normalisation returned no usable key"));
                continue;
            }
            if (documentIds.size() < request.consensusN()) {
                skipped.add(new SkippedGroup(key, group.label(), memberCodes, documentIds,
                        "one-off: appeared in " + documentIds.size() + " document(s);"
                                + " consensus needs ≥ " + request.consensusN()));
                continue;
            }
            eligible.add(new Eligible(key, group.label(), group.parentFamily(),
                    aliases(group, members), memberCodes, documentIds, members));
        }
        if (eligible.isEmpty()) {
            return new PromotionResult(candidate.docTypeCode(), null, List.of(), skipped,
                    candidate.proposals().size());
        }

        List<ClauseLabelNormalizer.LabelGroup> declaredTypes = new ArrayList<>();
        for (Eligible group : eligible) {
            declaredTypes.add(new ClauseLabelNormalizer.LabelGroup(
                    group.key(), group.label(), group.parentFamily(), group.aliases(),
                    group.memberCodes()));
        }
        DocTypePack candidatePack = withClauseTypes(base, declaredTypes, eligible);
        List<String> clusterDocIds = new ArrayList<>();
        for (Eligible group : eligible) {
            for (String documentId : group.documentIds()) {
                if (!clusterDocIds.contains(documentId)) {
                    clusterDocIds.add(documentId);
                }
            }
        }
        Set<String> sampleEntities = sampleEntities(eligible);

        List<String> rejectReasons = new ArrayList<>();
        EntityTokenFilter entityFilter = new EntityTokenFilter(sampleEntities);
        for (Eligible group : eligible) {
            if (entityFilter.isEntityToken(group.key())
                    || entityFilter.containsEntity(group.label())
                    || group.aliases().stream().anyMatch(entityFilter::containsEntity)) {
                rejectReasons.add("label_not_value: '" + group.key()
                        + "' carries an entity/value token");
            }
        }
        PackQualityGates.CoverageProbe probe = coverageProbe(base, eligible, clusterDocIds);
        PackQualityGates.GateReport report = PackQualityGates.evaluate(
                candidatePack,
                probe,
                request.minRequiredCoverage(),
                sampleEntities,
                clusterDocIds);
        if (!report.allPassed() || !rejectReasons.isEmpty()) {
            rejectReasons.addAll(0, report.rejectReasons());
            String reason = "quality gates: " + String.join("; ", rejectReasons);
            for (Eligible group : eligible) {
                skipped.add(new SkippedGroup(group.key(), group.label(), group.memberCodes(),
                        group.documentIds(), reason));
            }
            return new PromotionResult(candidate.docTypeCode(), null, List.of(), skipped,
                    candidate.proposals().size());
        }

        String industryTag = industryTag(request);
        PackStore.PublishedPack published = request.store().publish(new PackStore.PublishRequest(
                candidatePack,
                industryTag,
                null,
                List.of(Long.toString(request.mandateId())),
                "clause-promotion:" + candidate.docTypeCode(),
                clusterDocIds));

        List<String> gateMessages = new ArrayList<>();
        for (PackQualityGates.GateResult gate : report.gates()) {
            gateMessages.add(gate.name() + ": " + gate.detail());
        }
        List<PromotedType> promoted = new ArrayList<>();
        for (Eligible group : eligible) {
            promoted.add(new PromotedType(
                    published.pack().code(),
                    published.version(),
                    group.key(),
                    group.label(),
                    group.parentFamily(),
                    group.aliases(),
                    group.memberCodes(),
                    group.documentIds(),
                    gateMessages));
        }
        return new PromotionResult(candidate.docTypeCode(), published.version(), promoted, skipped,
                candidate.proposals().size());
    }

    private DocTypePack resolveBasePack(PromotionRequest request) {
        String code = request.candidate().docTypeCode();
        DocTypePack stored = request.store().latestForCode(code).orElse(null);
        return stored != null ? stored : request.basePacksByCode().get(code);
    }

    private String industryTag(PromotionRequest request) {
        String code = request.candidate().docTypeCode();
        String fromRegistry = request.store().latestEntryForCode(code)
                .map(PackStore.PackRegistryEntry::industryTag)
                .orElse(null);
        if (fromRegistry != null && !fromRegistry.isBlank()) {
            return fromRegistry;
        }
        DocTypePack base = request.basePacksByCode().get(code);
        if (base != null && !base.industryTags().isEmpty()) {
            return base.industryTags().get(0);
        }
        return "general";
    }

    private static DocTypePack withClauseTypes(DocTypePack base,
            List<ClauseLabelNormalizer.LabelGroup> groups, List<Eligible> eligible) {
        List<PackClauseType> types = new ArrayList<>(base.clauseTypes());
        for (Eligible group : eligible) {
            types.add(new PackClauseType(
                    group.key(), group.label(), group.parentFamily(), group.aliases()));
        }
        return new DocTypePack(
                base.code(), base.version(), base.family(), base.fileRole(),
                base.industryTags(), base.identitySignals(), base.fields(), base.tables(),
                base.checklist(), base.reportSections(), base.documentDateKind(),
                new ClauseSection(base.clauseSection().declared(),
                        base.clauseOpenExtraction(), types));
    }

    /**
     * The probe the M2 gates run on: the base pack's required fields were proven to
     * resolve when it was published, so they carry forward on the cluster documents;
     * the new evidence is each promoted type resolving on its own source documents.
     */
    private static PackQualityGates.CoverageProbe coverageProbe(DocTypePack base,
            List<Eligible> eligible, List<String> clusterDocIds) {
        Map<String, Set<String>> byDoc = new LinkedHashMap<>();
        for (String documentId : clusterDocIds) {
            byDoc.put(documentId, new LinkedHashSet<>());
        }
        for (PackField field : base.requiredFields()) {
            for (String documentId : clusterDocIds) {
                byDoc.get(documentId).add(field.key());
            }
        }
        for (Eligible group : eligible) {
            for (String documentId : group.documentIds()) {
                byDoc.computeIfAbsent(documentId, k -> new LinkedHashSet<>()).add(group.key());
            }
        }
        return new PackQualityGates.CoverageProbe(byDoc);
    }

    /** The aliases that bind the promoted type: the canonical label and every source label. */
    private static List<String> aliases(ClauseLabelNormalizer.LabelGroup group,
            List<OpenProposal> members) {
        List<String> out = new ArrayList<>();
        addAlias(out, group.label());
        for (String alias : group.aliases()) {
            addAlias(out, alias);
        }
        for (OpenProposal member : members) {
            addAlias(out, member.label());
        }
        return List.copyOf(out);
    }

    private static void addAlias(List<String> aliases, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        String cleaned = candidate.strip();
        for (String existing : aliases) {
            if (existing.equalsIgnoreCase(cleaned)) {
                return;
            }
        }
        aliases.add(cleaned);
    }

    private static Set<String> sampleEntities(List<Eligible> eligible) {
        Set<String> entities = new LinkedHashSet<>();
        for (Eligible group : eligible) {
            for (OpenProposal member : group.members()) {
                for (SourceDocument document : member.documents()) {
                    if (document.logicalName() != null && !document.logicalName().isBlank()) {
                        entities.add(document.logicalName().strip());
                    }
                    if (document.fileName() != null && !document.fileName().isBlank()) {
                        entities.add(document.fileName().strip());
                    }
                }
            }
        }
        return entities;
    }

    private static List<String> distinctDocumentIds(List<OpenProposal> members) {
        List<String> ids = new ArrayList<>();
        for (OpenProposal member : members) {
            for (SourceDocument document : member.documents()) {
                if (!ids.contains(document.id())) {
                    ids.add(document.id());
                }
            }
        }
        return List.copyOf(ids);
    }

    private static List<String> documentIds(List<SourceDocument> documents) {
        List<String> ids = new ArrayList<>();
        for (SourceDocument document : documents) {
            if (!ids.contains(document.id())) {
                ids.add(document.id());
            }
        }
        return List.copyOf(ids);
    }

    /** PackLoader clause-type keys are snake_case; a loose model key is normalised. */
    private static String sanitizeKey(String key) {
        if (key == null) {
            return "";
        }
        return key.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /** One group that passed consensus, before the quality gates decide its fate. */
    private record Eligible(
            String key,
            String label,
            String parentFamily,
            List<String> aliases,
            List<String> memberCodes,
            List<String> documentIds,
            List<OpenProposal> members) {}
}
