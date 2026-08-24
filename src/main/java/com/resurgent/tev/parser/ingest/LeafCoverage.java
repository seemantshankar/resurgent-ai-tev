package com.resurgent.tev.parser.ingest;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coverage-set algebra for composing cost-head contributions. Membership is
 * cell identity (and formula-reachable cells), never bounding boxes.
 */
final class LeafCoverage {

    enum Relation {
        DISJOINT,
        SUPERSET,
        IDENTICAL,
        PARTIAL
    }

    record Member(long regionId, Set<Long> coverage, BigDecimal amount) {}

    record Result(
            Relation relation,
            List<Long> amountRegionIds,
            List<Long> supersededRegionIds,
            List<Long> duplicateRegionIds,
            boolean blocksTrust,
            BigDecimal amount) {}

    private LeafCoverage() {}

    static Set<Long> expand(
            Set<Long> included, Long anchorId, Map<Long, List<Long>> precedents, Set<Long> universe) {
        Set<Long> seeds = new LinkedHashSet<>(included);
        if (anchorId != null) {
            seeds.add(anchorId);
        }
        Set<Long> seen = new LinkedHashSet<>(seeds);
        ArrayDeque<Long> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty()) {
            long id = queue.remove();
            for (long next : precedents.getOrDefault(id, List.of())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        Set<Long> coverage = new LinkedHashSet<>(included);
        for (long id : seen) {
            if (universe.contains(id)) {
                coverage.add(id);
            }
        }
        return coverage;
    }

    static Result compose(List<Member> members) {
        if (members.isEmpty()) {
            return new Result(Relation.DISJOINT, List.of(), List.of(), List.of(), false, BigDecimal.ZERO);
        }
        List<Member> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparingLong(Member::regionId));
        Set<Long> amountIds = new LinkedHashSet<>();
        Set<Long> superseded = new LinkedHashSet<>();
        Set<Long> duplicates = new LinkedHashSet<>();
        Relation relation = Relation.DISJOINT;
        boolean blocksTrust = false;
        for (Member member : ordered) {
            amountIds.add(member.regionId());
        }
        for (int i = 0; i < ordered.size(); i++) {
            for (int j = i + 1; j < ordered.size(); j++) {
                Member left = ordered.get(i);
                Member right = ordered.get(j);
                Relation pair = relate(left.coverage(), right.coverage());
                if (pair == Relation.PARTIAL) {
                    relation = Relation.PARTIAL;
                    blocksTrust = true;
                    amountIds.remove(right.regionId());
                } else if (pair == Relation.IDENTICAL) {
                    if (relation != Relation.PARTIAL) {
                        relation = Relation.IDENTICAL;
                    }
                    amountIds.remove(right.regionId());
                    duplicates.add(right.regionId());
                } else if (pair == Relation.SUPERSET) {
                    if (left.coverage().containsAll(right.coverage())
                            && left.coverage().size() > right.coverage().size()) {
                        amountIds.remove(right.regionId());
                        superseded.add(right.regionId());
                    } else {
                        amountIds.remove(left.regionId());
                        superseded.add(left.regionId());
                    }
                    if (relation == Relation.DISJOINT) {
                        relation = Relation.SUPERSET;
                    }
                }
            }
        }
        BigDecimal amount = BigDecimal.ZERO;
        for (Member member : ordered) {
            if (amountIds.contains(member.regionId()) && member.amount() != null) {
                amount = amount.add(member.amount());
            }
        }
        return new Result(
                relation,
                List.copyOf(amountIds),
                List.copyOf(superseded),
                List.copyOf(duplicates),
                blocksTrust,
                amount);
    }

    static Relation relate(Set<Long> left, Set<Long> right) {
        if (left.equals(right)) {
            return Relation.IDENTICAL;
        }
        if (left.isEmpty() || right.isEmpty() || disjoint(left, right)) {
            return Relation.DISJOINT;
        }
        if (left.containsAll(right) || right.containsAll(left)) {
            return Relation.SUPERSET;
        }
        return Relation.PARTIAL;
    }

    private static boolean disjoint(Set<Long> left, Set<Long> right) {
        for (long id : left) {
            if (right.contains(id)) {
                return false;
            }
        }
        return true;
    }
}
