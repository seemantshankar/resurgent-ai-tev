package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies coverage algebra and duplicate decisions to detected candidates.
 */
final class CandidateComposer {

    private CandidateComposer() {}

    static List<ExplicitAnchorDetector.Candidate> compose(
            String fileHash,
            List<ExplicitAnchorDetector.Candidate> detected,
            List<DuplicateDetector.Proposal> proposals,
            List<DuplicateDetector.Decision> decisions,
            Map<Long, List<Long>> precedents) {
        List<ExplicitAnchorDetector.Candidate> composed = new ArrayList<>();
        for (ExplicitAnchorDetector.Candidate candidate : detected) {
            composed.add(composeOne(fileHash, candidate, proposals, decisions, precedents));
        }
        return composed;
    }

    private static ExplicitAnchorDetector.Candidate composeOne(
            String fileHash,
            ExplicitAnchorDetector.Candidate candidate,
            List<DuplicateDetector.Proposal> proposals,
            List<DuplicateDetector.Decision> decisions,
            Map<Long, List<Long>> precedents) {
        List<ExplicitAnchorDetector.Contribution> calculated = new ArrayList<>();
        List<ExplicitAnchorDetector.Contribution> manuals = new ArrayList<>();
        for (ExplicitAnchorDetector.Contribution contribution : candidate.contributions()) {
            if ("manual".equals(contribution.basis())) {
                manuals.add(contribution);
            } else {
                calculated.add(contribution);
            }
        }
        Set<Long> universe = new LinkedHashSet<>();
        Map<Long, Set<Long>> included = new LinkedHashMap<>();
        for (ExplicitAnchorDetector.Contribution contribution : calculated) {
            Set<Long> ids = includedIds(contribution);
            included.put(contribution.regionId(), ids);
            universe.addAll(ids);
        }
        List<LeafCoverage.Member> members = new ArrayList<>();
        for (ExplicitAnchorDetector.Contribution contribution : calculated) {
            Set<Long> coverage = LeafCoverage.expand(
                    included.get(contribution.regionId()),
                    contribution.anchorCellId(),
                    precedents,
                    universe);
            members.add(new LeafCoverage.Member(contribution.regionId(), coverage));
        }
        LeafCoverage.Result coverage = calculated.size() < 2
                ? new LeafCoverage.Result(
                        LeafCoverage.Relation.DISJOINT,
                        calculated.stream().map(ExplicitAnchorDetector.Contribution::regionId).toList(),
                        List.of(), false)
                : LeafCoverage.compose(members);
        Set<Long> amountIds = new LinkedHashSet<>(coverage.amountRegionIds());
        Set<Long> persistIds = new LinkedHashSet<>();
        for (ExplicitAnchorDetector.Contribution contribution : calculated) {
            persistIds.add(contribution.regionId());
        }
        List<String> extra = new ArrayList<>();
        boolean forceReview = coverage.blocksTrust();
        if (coverage.blocksTrust()) {
            extra.add("PARTIAL_OVERLAP");
        }
        if (!coverage.supersededRegionIds().isEmpty()) {
            extra.add("SUPERSEDED_SUBSET");
            persistIds.removeAll(coverage.supersededRegionIds());
        }
        applyDecisions(calculated, proposals, decisions, amountIds, persistIds, extra);
        if (extra.contains("UNRESOLVED_DUPLICATE")) {
            forceReview = true;
        }
        if (!forceReview && extra.isEmpty() && calculated.size() <= 1) {
            return candidate;
        }
        List<ExplicitAnchorDetector.Contribution> persisted = new ArrayList<>();
        List<ExplicitAnchorDetector.Contribution> amountFrom = new ArrayList<>();
        for (ExplicitAnchorDetector.Contribution contribution : calculated) {
            if (persistIds.contains(contribution.regionId())) {
                persisted.add(contribution);
            }
            if (amountIds.contains(contribution.regionId())) {
                amountFrom.add(contribution);
            }
        }
        persisted.addAll(manuals);
        amountFrom.addAll(manuals);
        if (persisted.isEmpty()) {
            persisted = new ArrayList<>(candidate.contributions());
            amountFrom = persisted;
        }
        return ExplicitAnchorDetector.assemble(
                fileHash, candidate.costHeadId(), candidate.costHeadCode(),
                persisted, amountFrom, extra, forceReview);
    }

    private static void applyDecisions(
            List<ExplicitAnchorDetector.Contribution> calculated,
            List<DuplicateDetector.Proposal> proposals,
            List<DuplicateDetector.Decision> decisions,
            Set<Long> amountIds,
            Set<Long> persistIds,
            List<String> extra) {
        Map<Long, String> keys = new LinkedHashMap<>();
        for (ExplicitAnchorDetector.Contribution contribution : calculated) {
            keys.put(contribution.regionId(), contribution.regionKey());
        }
        for (DuplicateDetector.Proposal proposal : proposals) {
            if (!keys.containsKey(proposal.leftRegionId()) || !keys.containsKey(proposal.rightRegionId())) {
                continue;
            }
            DuplicateDetector.Decision decision = DuplicateDetector.Decision.latest(
                    decisions, proposal.leftRegionKey(), proposal.rightRegionKey());
            if (decision != null && "Distinct".equals(decision.decision())) {
                continue;
            }
            if (decision != null && "Duplicate".equals(decision.decision())) {
                String superseded = decision.supersededRegionKey();
                if (superseded != null && !superseded.isBlank()) {
                    for (Map.Entry<Long, String> entry : keys.entrySet()) {
                        if (superseded.equals(entry.getValue())) {
                            amountIds.remove(entry.getKey());
                            persistIds.remove(entry.getKey());
                        }
                    }
                    extra.add("DUPLICATE_SUPERSEDED");
                } else {
                    dropLaterFromAmount(calculated, proposal, amountIds);
                }
                continue;
            }
            extra.add("UNRESOLVED_DUPLICATE");
            dropLaterFromAmount(calculated, proposal, amountIds);
        }
    }

    private static void dropLaterFromAmount(
            List<ExplicitAnchorDetector.Contribution> calculated,
            DuplicateDetector.Proposal proposal,
            Set<Long> amountIds) {
        ExplicitAnchorDetector.Contribution left = null;
        ExplicitAnchorDetector.Contribution right = null;
        for (ExplicitAnchorDetector.Contribution contribution : calculated) {
            if (contribution.regionId() == proposal.leftRegionId()) {
                left = contribution;
            } else if (contribution.regionId() == proposal.rightRegionId()) {
                right = contribution;
            }
        }
        if (left == null || right == null) {
            return;
        }
        ExplicitAnchorDetector.Contribution drop =
                left.regionKey().compareTo(right.regionKey()) <= 0 ? right : left;
        amountIds.remove(drop.regionId());
    }

    private static Set<Long> includedIds(ExplicitAnchorDetector.Contribution contribution) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ExplicitAnchorDetector.CellParticipation cell : contribution.cells()) {
            if ("included".equals(cell.participation())) {
                ids.add(cell.cellId());
            }
        }
        return ids;
    }
}
