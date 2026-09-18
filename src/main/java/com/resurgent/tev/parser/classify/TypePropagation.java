package com.resurgent.tev.parser.classify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Types the hardcoded inputs, then carries those types forward through the formulas
 * by dimensional arithmetic until nothing changes.
 *
 * <p>Quantity times rate is money; money over money is a ratio; money divided by
 * 100,000 is money in lakhs. A sum requires its members to agree on both kind and
 * scale. Disagreement is refused rather than resolved, and a chain through a
 * {@code #REF!} or another workbook refuses too — a wrong binding is worse than a
 * missing one.
 *
 * <p>This must be a fixpoint rather than a fixed number of passes: on the working
 * FM the median derivation depth is 15 and the deepest chain is 266.
 */
final class TypePropagation {

    /** Guards against a pathological chain; far above the deepest real derivation. */
    static final int MAX_PASSES = 600;

    CellTypes resolve(CellGraph graph) {
        Objects.requireNonNull(graph, "graph");
        Map<Long, CellTypes.Typed> typed = new LinkedHashMap<>();
        Map<Long, UnboundReason> refusals = new LinkedHashMap<>();
        Set<Long> inCycle = cycles(graph);

        Map<String, List<InputCell>> bySeries = new LinkedHashMap<>();
        for (InputCell input : graph.inputs()) {
            String key = input.seriesKey() == null || input.seriesKey().isBlank()
                    ? "cell:" + input.cellId()
                    : input.seriesKey();
            bySeries.computeIfAbsent(key, ignored -> new ArrayList<>()).add(input);
        }
        for (List<InputCell> series : bySeries.values()) {
            // Type each member on its own evidence first: a row can mix a rate driver
            // (its own percent display and format) with money amounts that share only
            // the row label. The series then fills members that could not type alone,
            // so one period row is still one judgment where they agree.
            Map<Long, ResolvedUnit> own = new LinkedHashMap<>();
            for (InputCell input : series) {
                GraphCell cell = graph.cells().get(input.cellId());
                if (cell == null) {
                    continue;
                }
                ResolvedUnit candidate = InputTyping.of(cell);
                if (candidate.isResolved()) {
                    own.put(cell.cellId(), candidate);
                }
            }
            ResolvedUnit fallback = own.isEmpty()
                    ? ResolvedUnit.unresolved()
                    : own.values().iterator().next();
            for (InputCell input : series) {
                GraphCell cell = graph.cells().get(input.cellId());
                if (cell == null) {
                    continue;
                }
                ResolvedUnit unit = own.getOrDefault(cell.cellId(), fallback);
                if (unit.isResolved()) {
                    typed.put(cell.cellId(),
                            new CellTypes.Typed(unit, TypeSource.INPUT_LABEL, 0));
                } else {
                    refusals.put(cell.cellId(), UnboundReason.NO_LABEL);
                }
            }
        }
        for (long cellId : inCycle) {
            refusals.putIfAbsent(cellId, UnboundReason.CYCLE);
        }

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (GraphCell cell : graph.cells().values()) {
                if (!cell.isFormula() || !cell.numeric()) {
                    continue;
                }
                long cellId = cell.cellId();
                if (typed.containsKey(cellId) || refusals.containsKey(cellId)) {
                    continue;
                }
                Outcome outcome = derive(graph, cellId, typed, refusals);
                if (outcome.unit() != null) {
                    typed.put(cellId, new CellTypes.Typed(
                            outcome.unit(), TypeSource.PROPAGATED, outcome.depth()));
                    changed = true;
                } else if (outcome.refusal() != null) {
                    refusals.put(cellId, outcome.refusal());
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }

        // Whatever never settled has no usable typed input anywhere upstream.
        for (GraphCell cell : graph.cells().values()) {
            if (!cell.numeric()) {
                continue;
            }
            if (!typed.containsKey(cell.cellId()) && !refusals.containsKey(cell.cellId())) {
                refusals.put(cell.cellId(), UnboundReason.UNTYPABLE);
            }
        }

        Map<Long, ResolvedUnit> aggregationUnits =
                resolveAggregations(graph, typed, refusals);
        return new CellTypes(typed, refusals, aggregationUnits);
    }

    private record Outcome(ResolvedUnit unit, UnboundReason refusal, int depth) {

        static Outcome typed(ResolvedUnit unit, int depth) {
            return new Outcome(unit, null, depth);
        }

        static Outcome refused(UnboundReason refusal) {
            return new Outcome(null, refusal, 0);
        }

        static Outcome pending() {
            return new Outcome(null, null, 0);
        }
    }

    /**
     * One formula cell's unit from its operands. A barrier or a refused operand is
     * fatal: the chain is not trustworthy, so the cell refuses with that reason
     * rather than typing from whatever else it could still reach.
     */
    private Outcome derive(
            CellGraph graph,
            long cellId,
            Map<Long, CellTypes.Typed> typed,
            Map<Long, UnboundReason> refusals) {
        List<CellDependency> dependencies = graph.dependenciesOf(cellId);
        if (dependencies.isEmpty()) {
            return Outcome.refused(UnboundReason.UNTYPABLE);
        }

        List<Operand> summands = new ArrayList<>();
        List<Operand> factors = new ArrayList<>();
        List<Operand> divisors = new ArrayList<>();
        List<Operand> others = new ArrayList<>();
        boolean pending = false;
        int depth = 0;

        for (CellDependency dependency : dependencies) {
            if (dependency.isBarrier()) {
                return Outcome.refused(dependency.barrier());
            }
            if (dependency.isConstant()) {
                bucket(dependency.role(), summands, factors, divisors, others)
                        .add(Operand.constant(dependency.constant()));
                continue;
            }
            Long operandId = dependency.cellId();
            if (operandId == null) {
                continue;
            }
            UnboundReason refused = refusals.get(operandId);
            if (refused != null) {
                if (isBarrier(refused)) {
                    return Outcome.refused(refused);
                }
                continue;
            }
            CellTypes.Typed operand = typed.get(operandId);
            if (operand == null) {
                pending = true;
                continue;
            }
            depth = Math.max(depth, operand.depth() + 1);
            bucket(dependency.role(), summands, factors, divisors, others)
                    .add(Operand.typed(operand.unit()));
        }

        if (!summands.isEmpty()) {
            ResolvedUnit sum = combineSummands(summands);
            if (sum.refusal() != null) {
                return Outcome.refused(sum.refusal());
            }
            if (sum.isResolved()) {
                return Outcome.typed(sum, depth);
            }
        }
        if (!factors.isEmpty() || !divisors.isEmpty()) {
            ResolvedUnit product = combineProduct(factors, divisors);
            if (product.refusal() != null) {
                return Outcome.refused(product.refusal());
            }
            if (product.isResolved()) {
                return Outcome.typed(product, depth);
            }
        }
        if (!others.isEmpty()) {
            // Another function or a comparison: the operands still carry a dimension,
            // but only when they all agree on one.
            ResolvedUnit combined = combineSummands(others);
            if (combined.isResolved()) {
                return Outcome.typed(combined, depth);
            }
        }
        return pending ? Outcome.pending() : Outcome.refused(UnboundReason.UNTYPABLE);
    }

    /** A refused operand that poisons the chain, as opposed to one we can skip. */
    private static boolean isBarrier(UnboundReason reason) {
        return reason == UnboundReason.EXTERNAL_DEPENDENCY
                || reason == UnboundReason.BROKEN_DEPENDENCY
                || reason == UnboundReason.CYCLE
                || reason == UnboundReason.KIND_CONFLICT
                || reason == UnboundReason.SCALE_CONFLICT;
    }

    private static List<Operand> bucket(
            DependencyRole role,
            List<Operand> summands,
            List<Operand> factors,
            List<Operand> divisors,
            List<Operand> others) {
        return switch (role) {
            case SUMMAND_PLUS, SUMMAND_MINUS -> summands;
            case FACTOR -> factors;
            case DIVISOR -> divisors;
            case OTHER -> others;
        };
    }

    /** One operand: either a typed cell or a hardcoded number. */
    private record Operand(ResolvedUnit unit, Double constant) {

        static Operand typed(ResolvedUnit unit) {
            return new Operand(unit, null);
        }

        static Operand constant(double value) {
            return new Operand(null, value);
        }

        boolean isConstant() {
            return constant != null;
        }
    }

    /** A sum takes its members' unit, and only when every typed member agrees. */
    private static ResolvedUnit combineSummands(List<Operand> operands) {
        CellKind kind = null;
        CellScale scale = null;
        for (Operand operand : operands) {
            if (operand.isConstant()) {
                continue;
            }
            ResolvedUnit unit = operand.unit();
            if (!unit.isResolved()) {
                continue;
            }
            if (kind == null) {
                kind = unit.kind();
                scale = unit.scale();
                continue;
            }
            if (kind != unit.kind()) {
                return ResolvedUnit.refused(UnboundReason.KIND_CONFLICT);
            }
            if (scale != unit.scale()) {
                return ResolvedUnit.refused(UnboundReason.SCALE_CONFLICT);
            }
        }
        return kind == null ? ResolvedUnit.unresolved() : ResolvedUnit.of(kind, scale);
    }

    /**
     * Dimensional arithmetic over a product or quotient: quantity times rate is
     * money, money over money is a ratio, money over quantity is a rate, and a
     * constant divisor moves the scale — money divided by 100,000 is money in lakhs.
     */
    private static ResolvedUnit combineProduct(List<Operand> factors, List<Operand> divisors) {
        CellKind kind = null;
        CellScale scale = CellScale.UNIT;
        boolean sawTyped = false;

        for (Operand factor : factors) {
            if (factor.isConstant()) {
                CellScale moved = CellScale.multipliedBy(scale, factor.constant());
                scale = moved == null ? scale : moved;
                continue;
            }
            if (!factor.unit().isResolved()) {
                continue;
            }
            sawTyped = true;
            CellKind next = factor.unit().kind();
            scale = sawTyped && kind == null ? factor.unit().scale() : scale;
            kind = kind == null ? next : multiply(kind, next);
            if (kind == null) {
                return ResolvedUnit.unresolved();
            }
        }
        for (Operand divisor : divisors) {
            if (divisor.isConstant()) {
                CellScale moved = CellScale.dividedBy(scale, divisor.constant());
                if (moved == null) {
                    return ResolvedUnit.unresolved();
                }
                scale = moved;
                continue;
            }
            if (!divisor.unit().isResolved()) {
                continue;
            }
            sawTyped = true;
            kind = kind == null ? null : divide(kind, divisor.unit().kind());
            if (kind == null) {
                return ResolvedUnit.unresolved();
            }
        }
        if (kind == null) {
            return ResolvedUnit.unresolved();
        }
        return ResolvedUnit.of(kind, scale);
    }

    private static CellKind multiply(CellKind left, CellKind right) {
        if (left == CellKind.PERCENT || left == CellKind.RATIO) {
            return right;
        }
        if (right == CellKind.PERCENT || right == CellKind.RATIO) {
            return left;
        }
        if (left == CellKind.MONEY || right == CellKind.MONEY) {
            return CellKind.MONEY;
        }
        boolean leftCounts = left == CellKind.QUANTITY || left == CellKind.COUNT;
        boolean rightCounts = right == CellKind.QUANTITY || right == CellKind.COUNT;
        if (left == CellKind.RATE && rightCounts) {
            return CellKind.MONEY;
        }
        if (right == CellKind.RATE && leftCounts) {
            return CellKind.MONEY;
        }
        if (leftCounts && rightCounts) {
            return CellKind.QUANTITY;
        }
        return null;
    }

    private static CellKind divide(CellKind numerator, CellKind denominator) {
        if (numerator == denominator) {
            return CellKind.RATIO;
        }
        if (denominator == CellKind.PERCENT || denominator == CellKind.RATIO) {
            return numerator;
        }
        if (numerator == CellKind.MONEY
                && (denominator == CellKind.QUANTITY || denominator == CellKind.COUNT)) {
            return CellKind.RATE;
        }
        return null;
    }

    /**
     * Each aggregation's unit, resolved once from its members. A group whose members
     * disagree is refused, so no member of it can take a cost role on the strength
     * of a unit nobody could settle.
     */
    private static Map<Long, ResolvedUnit> resolveAggregations(
            CellGraph graph,
            Map<Long, CellTypes.Typed> typed,
            Map<Long, UnboundReason> refusals) {
        Map<Long, ResolvedUnit> units = new LinkedHashMap<>();
        for (Aggregation aggregation : graph.aggregations()) {
            CellKind kind = null;
            CellScale scale = null;
            UnboundReason refusal = null;
            for (Aggregation.Member member : aggregation.members()) {
                CellTypes.Typed row = typed.get(member.cellId());
                if (row == null) {
                    continue;
                }
                if (kind == null) {
                    kind = row.unit().kind();
                    scale = row.unit().scale();
                    continue;
                }
                if (kind != row.unit().kind()) {
                    refusal = UnboundReason.KIND_CONFLICT;
                    break;
                }
                if (scale != row.unit().scale()) {
                    refusal = UnboundReason.SCALE_CONFLICT;
                    break;
                }
            }
            if (refusal != null) {
                units.put(aggregation.headCellId(), ResolvedUnit.refused(refusal));
                refusals.putIfAbsent(aggregation.headCellId(), refusal);
                continue;
            }
            if (kind == null) {
                units.put(aggregation.headCellId(), ResolvedUnit.unresolved());
                continue;
            }
            ResolvedUnit unit = ResolvedUnit.of(kind, scale);
            units.put(aggregation.headCellId(), unit);
            // A head that could not type on its own still takes its group's unit:
            // the group is what the head is a total of.
            typed.computeIfAbsent(aggregation.headCellId(),
                    id -> new CellTypes.Typed(unit, TypeSource.AGGREGATION, 1));
        }
        return units;
    }

    /**
     * Cells that sit on a reference cycle. Found once by iterative depth-first
     * search, so a circular workbook terminates with a reason instead of spinning.
     */
    private static Set<Long> cycles(CellGraph graph) {
        Set<Long> onCycle = new LinkedHashSet<>();
        Map<Long, Integer> state = new HashMap<>();
        for (Long start : graph.cells().keySet()) {
            if (state.containsKey(start)) {
                continue;
            }
            List<Long> stack = new ArrayList<>();
            Set<Long> onStack = new HashSet<>();
            walk(graph, start, state, stack, onStack, onCycle);
        }
        return onCycle;
    }

    private static void walk(
            CellGraph graph,
            long start,
            Map<Long, Integer> state,
            List<Long> stack,
            Set<Long> onStack,
            Set<Long> onCycle) {
        List<Frame> frames = new ArrayList<>();
        frames.add(new Frame(start, 0));
        state.put(start, 1);
        onStack.add(start);
        stack.add(start);
        while (!frames.isEmpty()) {
            Frame frame = frames.get(frames.size() - 1);
            List<CellDependency> dependencies = graph.dependenciesOf(frame.cellId());
            if (frame.next() >= dependencies.size()) {
                state.put(frame.cellId(), 2);
                onStack.remove(frame.cellId());
                stack.remove(stack.size() - 1);
                frames.remove(frames.size() - 1);
                continue;
            }
            CellDependency dependency = dependencies.get(frame.next());
            frames.set(frames.size() - 1, new Frame(frame.cellId(), frame.next() + 1));
            Long next = dependency.cellId();
            if (next == null) {
                continue;
            }
            if (onStack.contains(next)) {
                // Everything from where the cycle closes back to here is on it.
                int from = stack.lastIndexOf(next);
                onCycle.addAll(stack.subList(from, stack.size()));
                continue;
            }
            if (state.getOrDefault(next, 0) == 0) {
                state.put(next, 1);
                onStack.add(next);
                stack.add(next);
                frames.add(new Frame(next, 0));
            }
        }
    }

    private record Frame(long cellId, int next) {}
}
