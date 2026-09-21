package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Binds from the cell graph, without asking anything. A cell binds only when the
 * graph proves its role and its own label resolves unambiguously to a hard catalog
 * leaf consistent with its group's unit. Everything else is left unbound with a
 * reason, or queued for the group-level question.
 *
 * <p>Role comes from the graph rather than from the formula-vs-literal distinction:
 * a cell is a rollup exactly when it is an aggregation head, which is what the old
 * formula role gate was reaching for when it forced every formula to helper or
 * total and left the binding table contributing nothing to any leaf rollup.
 */
final class DeterministicBinder {

    /** What the binder concluded for one parse run. */
    record Result(
            List<NomenclatureBinding> bindings,
            Map<Long, UnboundReason> unboundReasons,
            List<QueuedGroup> queued,
            Map<Long, Long> headCellByCell,
            Set<Long> provenRoleCells) {

        Result {
            bindings = List.copyOf(bindings);
            unboundReasons = Map.copyOf(unboundReasons);
            queued = List.copyOf(queued);
            headCellByCell = Map.copyOf(headCellByCell);
            provenRoleCells = Set.copyOf(provenRoleCells);
        }

        /**
         * True when an aggregation gave this cell its role. Where no aggregation reads
         * a cell, the graph has no evidence of how it participates, so a per-cell
         * answer that says otherwise — a contra line a formula never subtracts, say —
         * is not contradicted by anything and still stands.
         */
        boolean roleProven(long cellId) {
            return provenRoleCells.contains(cellId);
        }
    }

    /**
     * A cell whose role the graph knows but whose label the catalog does not, so the
     * name is the only open question. Queued by label, not by cell.
     */
    record QueuedGroup(
            QualifiedLabel label,
            long cellId,
            long candidateId,
            String amountRole,
            String source,
            Long headCellId) {}

    Result bind(
            long parseRunId,
            CellGraph graph,
            CellTypes types,
            OntologySlice slice,
            Map<Long, Long> candidateByCell,
            Set<Long> alreadyBound) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(slice, "slice");

        List<NomenclatureBinding> bindings = new ArrayList<>();
        Map<Long, UnboundReason> reasons = new LinkedHashMap<>();
        List<QueuedGroup> queued = new ArrayList<>();
        Map<Long, Long> headCellByCell = new LinkedHashMap<>();
        Set<Long> provenRoleCells = new LinkedHashSet<>();

        for (GraphCell cell : graph.cells().values()) {
            if (!cell.numeric() || alreadyBound.contains(cell.cellId())) {
                continue;
            }
            Long candidateId = candidateByCell.get(cell.cellId());
            if (candidateId == null) {
                continue;
            }
            Placement placement = placementOf(graph, types, cell);
            if (placement.reason() != null) {
                reasons.put(cell.cellId(), placement.reason());
                continue;
            }
            if (placement.headCellId() != null) {
                headCellByCell.put(cell.cellId(), placement.headCellId());
            }
            if (placement.roleProven()) {
                provenRoleCells.add(cell.cellId());
            }

            String label = placement.label();
            if (label == null || label.isBlank()) {
                reasons.put(cell.cellId(), UnboundReason.NO_LABEL);
                continue;
            }
            OntologySlice.Resolution resolution = slice.resolveLabel(label);
            if (resolution.kind() == OntologySlice.Resolution.Kind.AMBIGUOUS) {
                reasons.put(cell.cellId(), UnboundReason.AMBIGUOUS_LABEL);
                continue;
            }
            Optional<String> hardLeaf = resolution.unique().filter(path -> isHardLeaf(slice, path));
            if (hardLeaf.isEmpty()) {
                // The role is proven; only the name is open. Ask once per label per
                // worksheet, so the question carries the cell's own sheet context.
                queued.add(new QueuedGroup(
                        new QualifiedLabel(cell.worksheetId(), placement.groupLabel(), label),
                        cell.cellId(),
                        candidateId,
                        placement.role(),
                        placement.source(),
                        placement.headCellId()));
                reasons.put(cell.cellId(), UnboundReason.LLM_UNAVAILABLE);
                continue;
            }

            bindings.add(new NomenclatureBinding(
                    cell.cellId(),
                    parseRunId,
                    candidateId,
                    label,
                    hardLeaf.get(),
                    placement.role(),
                    false,
                    false,
                    null,
                    placement.source(),
                    new QualifiedLabel(cell.worksheetId(), placement.groupLabel(), label).key(),
                    null));
        }
        return new Result(bindings, reasons, queued, headCellByCell, provenRoleCells);
    }

    /** Where one cell sits: the role the graph gives it, or the reason it has none. */
    private record Placement(
            String role,
            String source,
            String label,
            String groupLabel,
            Long headCellId,
            UnboundReason reason) {

        static Placement refused(UnboundReason reason) {
            return new Placement(null, null, null, null, null, reason);
        }

        /** An aggregation gave the role; a standalone line's role is only a default. */
        boolean roleProven() {
            return headCellId != null;
        }
    }

    private static Placement placementOf(CellGraph graph, CellTypes types, GraphCell cell) {
        Optional<UnboundReason> refused = types.refusalOf(cell.cellId());
        if (refused.isPresent()) {
            return Placement.refused(refused.get());
        }
        Optional<ResolvedUnit> unit = types.unitOf(cell.cellId());
        if (unit.isEmpty()) {
            return Placement.refused(UnboundReason.UNTYPABLE);
        }

        Optional<Aggregation> headed = graph.aggregationHeadedBy(cell.cellId());
        if (headed.isPresent()) {
            ResolvedUnit groupUnit = types.unitOf(headed.get());
            if (!groupUnit.isMoney()) {
                return Placement.refused(groupUnit.refusal() == null
                        ? UnboundReason.NON_MONEY_GROUP
                        : groupUnit.refusal());
            }
            return new Placement(
                    AmountRole.TOTAL,
                    BindingSource.AGGREGATION_HEAD,
                    cell.rowLabel(),
                    headed.get().headLabel(),
                    cell.cellId(),
                    null);
        }

        List<Aggregation> memberships = graph.membershipsOf(cell.cellId());
        if (!memberships.isEmpty()) {
            // A cell may add into one group and be deducted in another. The row's own
            // rollup speaks for it first — a cross-row total must not overwrite the
            // operator the cell's own row applied; otherwise the group that adds it
            // names it, so a deduct-only cell takes its deduct.
            Aggregation owner = null;
            Aggregation.Member ownerMember = null;
            for (Aggregation aggregation : memberships) {
                ResolvedUnit groupUnit = types.unitOf(aggregation);
                if (!groupUnit.isMoney()) {
                    continue;
                }
                for (Aggregation.Member member : aggregation.members()) {
                    if (member.cellId() != cell.cellId()) {
                        continue;
                    }
                    if (owner == null
                            || preferredOwner(graph, cell, aggregation, member, owner, ownerMember)) {
                        owner = aggregation;
                        ownerMember = member;
                    }
                }
            }
            if (owner == null) {
                return Placement.refused(UnboundReason.NON_MONEY_GROUP);
            }
            return new Placement(
                    ownerMember.amountRole(),
                    graph.dependenciesOf(cell.cellId()).isEmpty()
                            ? BindingSource.INPUT
                            : BindingSource.DERIVED,
                    cell.rowLabel(),
                    owner.headLabel(),
                    owner.headCellId(),
                    null);
        }

        if (graph.isDriverOnly(cell.cellId())) {
            return Placement.refused(UnboundReason.DRIVER_ONLY);
        }
        if (!unit.get().isMoney()) {
            return Placement.refused(UnboundReason.NON_MONEY_GROUP);
        }
        // Money that no aggregation reads: it is a standalone line, not a rollup.
        return new Placement(
                AmountRole.ADD,
                graph.dependenciesOf(cell.cellId()).isEmpty()
                        ? BindingSource.INPUT
                        : BindingSource.DERIVED,
                cell.rowLabel(),
                null,
                null,
                null);
    }

    /**
     * The owning group for a member: the aggregation whose head sits in the member's
     * own row outranks a cross-row total, so a net line's operator is taken as written
     * rather than being replaced by a column total that also sums the cell. Ties go to
     * the group that adds the cell, which is how a contra line keeps its sign.
     */
    private static boolean preferredOwner(
            CellGraph graph,
            GraphCell cell,
            Aggregation candidate,
            Aggregation.Member candidateMember,
            Aggregation owner,
            Aggregation.Member ownerMember) {
        boolean candidateSameRow = sameRowAsHead(graph, cell, candidate);
        boolean ownerSameRow = sameRowAsHead(graph, cell, owner);
        if (candidateSameRow != ownerSameRow) {
            return candidateSameRow;
        }
        return candidateMember.plus() && !ownerMember.plus();
    }

    private static boolean sameRowAsHead(CellGraph graph, GraphCell cell, Aggregation aggregation) {
        GraphCell head = graph.cells().get(aggregation.headCellId());
        return head != null && head.rowNum() == cell.rowNum();
    }

    private static boolean isHardLeaf(OntologySlice slice, String path) {
        Optional<NomenclatureNode> node = slice.node(path);
        return node.isPresent()
                && node.get().leaf()
                && !NomenclatureNode.LAYER_MANDATE_SOFT.equals(node.get().layer());
    }
}
