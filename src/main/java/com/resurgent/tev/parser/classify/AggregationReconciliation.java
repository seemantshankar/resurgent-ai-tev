package com.resurgent.tev.parser.classify;

import java.util.ArrayList;
import java.util.List;

/**
 * The value check that catches a wrong scale from the workbook's own arithmetic.
 *
 * <p>An additive head is defined by its formula: {@code K54 = I54 - J54},
 * {@code J62 = J52 + J55 + ...}. Whatever scale each cell is typed at, the identity
 * holds in the worksheet's own numbers, so after normalising every member and the
 * head by its assigned scale the two sides must agree:
 *
 * <pre>{@code
 * sum(sign * memberValue * memberScaleMultiplier) == headValue * headScaleMultiplier
 * }</pre>
 *
 * <p>A triplet that does not reconcile is proof that at least one assigned scale is
 * wrong — the tagged values cannot be the worksheet's own figures. On row 54 this is
 * what exposes J54: with I54 typed lakh and J54 typed unit the net is out by a factor
 * of 100,000, while {@code I54 = 120.2999616} and {@code J54 = 120.3} being the same
 * number to seven figures is only possible if both are lakh.
 *
 * <p>This is the same technique as the extension idiom — reading a label against the
 * arithmetic the workbook performs on it — applied to scales rather than amounts. It
 * is a detector, not an adjudicator: it reports the aggregations whose numbers cannot
 * all be right, and leaves the fix to adoption or to the unstated-scale guard.
 */
final class AggregationReconciliation {

    /** One additive group whose tagged values do not reconcile. */
    record Mismatch(
            long headCellId,
            long worksheetId,
            String headCoord,
            double headNormalized,
            double membersNormalized,
            int membersChecked) {

        double difference() {
            return membersNormalized - headNormalized;
        }
    }

    /** The whole check: how many groups were verified, skipped, and found wrong. */
    record Report(int checked, int skipped, List<Mismatch> mismatches) {

        Report {
            mismatches = List.copyOf(mismatches);
        }

        boolean reconciled() {
            return mismatches.isEmpty();
        }
    }

    /** Relative tolerance: doubles from a worksheet, normalised to base units. */
    private static final double RELATIVE_TOLERANCE = 1e-6;

    private AggregationReconciliation() {}

    static Report check(CellGraph graph, CellTypes types) {
        int checked = 0;
        int skipped = 0;
        List<Mismatch> mismatches = new ArrayList<>();
        for (Aggregation aggregation : graph.aggregations()) {
            GraphCell head = graph.cells().get(aggregation.headCellId());
            Double headValue = numericValue(head);
            ResolvedUnit headUnit = types.unitOf(aggregation.headCellId()).orElse(null);
            ScaleProvenance headProvenance =
                    types.scaleProvenanceOf(aggregation.headCellId()).orElse(null);
            if (head == null || headValue == null || headUnit == null || !headUnit.isMoney()
                    || headProvenance == null || !headProvenance.isClaimed()) {
                skipped++;
                continue;
            }
            if (!membersAccountForTheHead(graph, aggregation.headCellId())) {
                // An addend that is a function call — {@code ROUND(SUM(I261:I273),2)}
                // inside {@code TOTAL CURRENT ASSETS} — is not a member of the tracked
                // relation, so the member list cannot account for the head's value.
                // That is a gap in what the check can see, not a scale error, and
                // reporting it would bury the real ones.
                skipped++;
                continue;
            }

            double headNormalized = headValue * headUnit.scale().multiplier();
            double members = 0d;
            int counted = 0;
            boolean usable = true;
            for (Aggregation.Member member : aggregation.members()) {
                GraphCell cell = graph.cells().get(member.cellId());
                Double value = numericValue(cell);
                if (value == null) {
                    // A blank member contributes nothing to a SUM, so it is not a
                    // missing scale; any other unusable member makes the group
                    // unverifiable rather than wrong.
                    if (cell == null || cell.valueType() == null
                            || !cell.valueType().toLowerCase(java.util.Locale.ROOT).contains("empty")) {
                        usable = false;
                        break;
                    }
                    continue;
                }
                ResolvedUnit unit = types.unitOf(member.cellId()).orElse(null);
                ScaleProvenance provenance =
                        types.scaleProvenanceOf(member.cellId()).orElse(null);
                if (unit == null || !unit.isMoney() || provenance == null
                        || !provenance.isClaimed()) {
                    usable = false;
                    break;
                }
                double signed = member.plus() ? value : -value;
                members += signed * unit.scale().multiplier();
                counted++;
            }
            if (!usable) {
                skipped++;
                continue;
            }
            // A literal in an additive formula is stated at the group's scale: the
            // worksheet adds raw numbers, so a constant added to lakh cells is lakh.
            // The tracked relation carries no scale of its own, so it takes the head's.
            for (CellDependency dependency : graph.dependenciesOf(aggregation.headCellId())) {
                if (!dependency.isConstant() || !dependency.role().isSummand()) {
                    continue;
                }
                double sign =
                        dependency.role() == DependencyRole.SUMMAND_MINUS ? -1d : 1d;
                members += sign * dependency.constant() * headUnit.scale().multiplier();
            }
            checked++;
            double tolerance =
                    RELATIVE_TOLERANCE * Math.max(1d, Math.max(Math.abs(headNormalized), Math.abs(members)));
            if (Math.abs(members - headNormalized) > tolerance) {
                mismatches.add(new Mismatch(
                        aggregation.headCellId(),
                        aggregation.worksheetId(),
                        head.coord(),
                        headNormalized,
                        members,
                        counted));
            }
        }
        return new Report(checked, skipped, mismatches);
    }

    /**
     * True when every operand of the head's expression is either a summand the tracked
     * relation lists as a member, or a literal the check adds itself at the group's
     * scale. Anything else — a function call, a factor, a barrier — means the member
     * list is not the whole expression, so the arithmetic cannot be verified.
     */
    private static boolean membersAccountForTheHead(CellGraph graph, long headCellId) {
        for (CellDependency dependency : graph.dependenciesOf(headCellId)) {
            if (dependency.isConstant()) {
                continue;
            }
            if (!dependency.role().isSummand()) {
                return false;
            }
        }
        return true;
    }

    private static Double numericValue(GraphCell cell) {
        if (cell == null || !cell.numeric() || cell.error()) {
            return null;
        }
        String text = cell.numericValue();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
