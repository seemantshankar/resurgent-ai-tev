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
            Map<Long, InputTyping.Reading> own = new LinkedHashMap<>();
            for (InputCell input : series) {
                GraphCell cell = graph.cells().get(input.cellId());
                if (cell == null) {
                    continue;
                }
                InputTyping.Reading candidate = InputTyping.readingOf(cell);
                if (candidate.unit().isResolved()) {
                    own.put(cell.cellId(), candidate);
                }
            }
            InputTyping.Reading fallback =
                    new InputTyping.Reading(ResolvedUnit.unresolved(), false);
            for (InputTyping.Reading candidate : own.values()) {
                if (!fallback.unit().isResolved()) {
                    fallback = candidate;
                    continue;
                }
                if (fallback.unit().kind() != candidate.unit().kind()
                        || fallback.unit().scale() != candidate.unit().scale()) {
                    fallback = new InputTyping.Reading(ResolvedUnit.unresolved(), false);
                    break;
                }
            }
            for (InputCell input : series) {
                GraphCell cell = graph.cells().get(input.cellId());
                if (cell == null) {
                    continue;
                }
                InputTyping.Reading reading = own.getOrDefault(cell.cellId(), fallback);
                if (reading.unit().isResolved()) {
                    typed.put(cell.cellId(), new CellTypes.Typed(
                            reading.unit(), TypeSource.INPUT_LABEL, 0,
                            false, reading.bareDefault()));
                } else {
                    refusals.put(cell.cellId(), UnboundReason.NO_LABEL);
                }
            }
        }
        for (long cellId : inCycle) {
            refusals.putIfAbsent(cellId, UnboundReason.CYCLE);
        }
        Set<Long> inputIds = new HashSet<>();
        for (InputCell input : graph.inputs()) {
            inputIds.add(input.cellId());
        }

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (GraphCell cell : graph.cells().values()) {
                if (!cell.isFormula() || !cell.numeric()) {
                    continue;
                }
                long cellId = cell.cellId();
                if (inputIds.contains(cellId) || refusals.containsKey(cellId)) {
                    continue;
                }
                // A formula is re-derived every pass rather than frozen the first time it
                // yields something. An operand that is still pending is skipped, so an
                // early reading is a partial one; when the operand later types or refuses
                // the formula must be revisited, or a subset guess (percent from the
                // percent factor alone, money from the money factor alone) would be
                // written and never corrected.
                Outcome outcome = derive(graph, cellId, typed, refusals);
                if (outcome.unit() != null) {
                    CellTypes.Typed next = new CellTypes.Typed(
                            outcome.unit(), TypeSource.PROPAGATED, outcome.depth(),
                            outcome.weak(), outcome.bareDefault());
                    CellTypes.Typed current = typed.get(cellId);
                    if (current == null
                            || !current.unit().equals(next.unit())
                            || current.depth() != next.depth()
                            || current.weak() != next.weak()
                            || current.bareDefault() != next.bareDefault()) {
                        typed.put(cellId, next);
                        changed = true;
                    }
                } else if (outcome.refusal() != null) {
                    typed.remove(cellId);
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

    private record Outcome(
            ResolvedUnit unit, UnboundReason refusal, int depth, Strength strength) {

        static Outcome typed(ResolvedUnit unit, int depth, Strength strength) {
            return new Outcome(unit, null, depth, strength);
        }

        static Outcome refused(UnboundReason refusal) {
            return new Outcome(null, refusal, 0, Strength.strong());
        }

        static Outcome pending() {
            return new Outcome(null, null, 0, Strength.strong());
        }

        boolean weak() {
            return strength.weak();
        }

        boolean bareDefault() {
            return strength.bareDefault();
        }
    }

    /**
     * How much a resolved kind can be trusted. {@code weak} marks a kind the
     * arithmetic could only guess — a product whose dimension rested on a single
     * typed operand while hardcoded literals carried the rest, so a count times
     * constants may be a count or a per-unit amount. {@code bareDefault} marks a
     * kind resting on the money default rather than a stated cue. Weakness is a
     * property of how the kind was obtained, not of which kind it is.
     */
    private record Strength(boolean weak, boolean bareDefault) {

        static Strength strong() {
            return new Strength(false, false);
        }

        Strength and(Strength other) {
            return new Strength(weak || other.weak, bareDefault && other.bareDefault);
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
        // A product is not the product of a subset of its factors. A skipped or still
        // unknown factor leaves the dimension unproven, so the product stays pending
        // (it may yet resolve) or refuses (it never will) instead of returning whatever
        // the remaining factors happen to multiply to.
        boolean productPending = false;
        boolean productBlocked = false;
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
                if (dependency.role().isDriver()) {
                    productBlocked = true;
                }
                continue;
            }
            CellTypes.Typed operand = typed.get(operandId);
            if (operand == null) {
                pending = true;
                if (dependency.role().isDriver()) {
                    productPending = true;
                }
                continue;
            }
            depth = Math.max(depth, operand.depth() + 1);
            bucket(dependency.role(), summands, factors, divisors, others)
                    .add(Operand.typed(
                            operand.unit(), operand.weak(), operand.bareDefault()));
        }

        // A scale no cue stated is not a claim of rupees; it adopts the scale the
        // typed operands agree on. J26 = 314.4 is a bare literal with no label, no
        // divisor and no header, so money/unit was an unearned assertion; in
        // I26 - J26, where I26 is money/lakh, J26 is lakh. A cell whose kind came
        // from a stated cue (Amount in Rs) keeps its own scale, so a genuine
        // rupees-against-lakhs conflict is still refused.
        adoptUnstatedScale(summands);
        adoptUnstatedScale(factors);
        adoptUnstatedScale(divisors);
        adoptUnstatedScale(others);

        if (!summands.isEmpty()) {
            ResolvedUnit sum = combineSummands(summands);
            if (sum.refusal() != null) {
                return Outcome.refused(sum.refusal());
            }
            if (sum.isResolved()) {
                return Outcome.typed(sum, depth, strengthOf(summands, false));
            }
        }
        if (!factors.isEmpty() || !divisors.isEmpty()) {
            if (productPending) {
                return Outcome.pending();
            }
            if (productBlocked) {
                return Outcome.refused(UnboundReason.UNTYPABLE);
            }
            ResolvedUnit product = combineProduct(factors, divisors);
            if (product.refusal() != null) {
                return Outcome.refused(product.refusal());
            }
            if (product.isResolved()) {
                Strength strength = strengthOf(
                        productOperands(factors, divisors), ownWeakProduct(factors, divisors));
                // A kind the arithmetic could only guess does not outvote the group's
                // members whose kinds were actually known. If a group the cell adds
                // into has non-weak members that agree on a kind, that kind is the
                // answer; the scale stays the cell's own, because it comes from this
                // formula's divisor. A weak cell with no such group keeps its own
                // reading, so a unanimous group of counts stays a count (H15, H25).
                if (strength.weak()) {
                    CellKind settledKind = strongConsensusKind(graph, cellId, typed);
                    if (settledKind != null) {
                        return Outcome.typed(
                                ResolvedUnit.of(settledKind, product.scale()),
                                depth,
                                Strength.strong());
                    }
                }
                return Outcome.typed(product, depth, strength);
            }
        }
        if (!others.isEmpty()) {
            // Another function or a comparison: the operands still carry a dimension,
            // but only when they all agree on one.
            ResolvedUnit combined = combineSummands(others);
            if (combined.isResolved()) {
                return Outcome.typed(combined, depth, strengthOf(others, false));
            }
        }
        return pending ? Outcome.pending() : Outcome.refused(UnboundReason.UNTYPABLE);
    }

    /**
     * A bucket of operands whose unstated scales adopt the one scale the typed
     * operands state. When no operand states a scale, or the stated ones disagree,
     * nothing changes and the combination decides as before.
     */
    private static void adoptUnstatedScale(List<Operand> operands) {
        CellScale stated = null;
        for (Operand operand : operands) {
            if (operand.isConstant() || operand.scaleUnstated()) {
                continue;
            }
            if (stated == null) {
                stated = operand.unit().scale();
            } else if (stated != operand.unit().scale()) {
                return;
            }
        }
        if (stated == null) {
            return;
        }
        for (int index = 0; index < operands.size(); index++) {
            Operand operand = operands.get(index);
            if (operand.scaleUnstated()) {
                operands.set(index, operand.withScale(stated));
            }
        }
    }

    /** The typed operands of a product: its factors and typed divisors. */
    private static List<Operand> productOperands(List<Operand> factors, List<Operand> divisors) {
        List<Operand> typedOperands = new ArrayList<>();
        for (Operand operand : factors) {
            if (!operand.isConstant()) {
                typedOperands.add(operand);
            }
        }
        for (Operand operand : divisors) {
            if (!operand.isConstant()) {
                typedOperands.add(operand);
            }
        }
        return typedOperands;
    }

    /**
     * True when a product's dimension rested on a single typed operand while a
     * hardcoded literal factor carried the rest — {@code (F25*0.15*500*300)/100000}
     * and {@code D145*1}. Two typed contributors settle a product on their own
     * ({@code G15*D15*365/100000} is money regardless of the 365), so those stay
     * strong.
     *
     * <p>An operand already known to be money never makes a product weak. The failure
     * this guards against is a count that should have become money, and no unmarked
     * literal can demote money to a count — it can only move the scale. So
     * {@code I60*100000}, a lakh-to-rupee conversion, is a settled money figure.
     */
    private static boolean ownWeakProduct(List<Operand> factors, List<Operand> divisors) {
        List<Operand> typedOperands = productOperands(factors, divisors);
        long constantFactors = factors.stream().filter(Operand::isConstant).count();
        return typedOperands.size() == 1
                && constantFactors >= 1
                && typedOperands.get(0).unit().kind() != CellKind.MONEY;
    }

    /** How much a bucket of operands can be trusted: weak if any contributor was. */
    private static Strength strengthOf(List<Operand> contributing, boolean ownWeak) {
        boolean weak = ownWeak;
        boolean bareDefault = true;
        boolean anyTyped = false;
        for (Operand operand : contributing) {
            if (operand.isConstant()) {
                continue;
            }
            anyTyped = true;
            weak |= operand.weak();
            bareDefault &= operand.bareDefault();
        }
        return new Strength(weak, anyTyped && bareDefault);
    }

    /**
     * The kind a weak cell's group has settled on, or {@code null} when no group the
     * cell belongs to has agreeing non-weak members. A group whose strong members
     * disagree yields nothing, and a winner with no supporter that is more than the
     * bare money default yields nothing: the default is the weakest evidence in the
     * system and must not outvote a derived kind. Only the kind is taken — the scale
     * belongs to the cell's own formula.
     */
    private static CellKind strongConsensusKind(
            CellGraph graph, long cellId, Map<Long, CellTypes.Typed> typed) {
        CellKind found = null;
        for (Aggregation aggregation : graph.membershipsOf(cellId)) {
            CellKind kind = strongKindOf(aggregation, cellId, typed);
            if (kind == null) {
                continue;
            }
            if (found == null) {
                found = kind;
            } else if (found != kind) {
                // Two groups the cell adds into disagree: no safe reading.
                return null;
            }
        }
        return found;
    }

    private static CellKind strongKindOf(
            Aggregation aggregation, long cellId, Map<Long, CellTypes.Typed> typed) {
        CellKind kind = null;
        boolean nonDefault = false;
        for (Aggregation.Member member : aggregation.members()) {
            if (member.cellId() == cellId) {
                continue;
            }
            CellTypes.Typed row = typed.get(member.cellId());
            if (row == null || row.weak()) {
                continue;
            }
            if (kind == null) {
                kind = row.unit().kind();
            } else if (kind != row.unit().kind()) {
                return null;
            }
            if (!row.bareDefault()) {
                nonDefault = true;
            }
        }
        return kind != null && nonDefault ? kind : null;
    }

    /** A refused operand that poisons the chain, as opposed to one we can skip. */
    private static boolean isBarrier(UnboundReason reason) {
        return reason == UnboundReason.EXTERNAL_DEPENDENCY
                || reason == UnboundReason.BROKEN_DEPENDENCY
                || reason == UnboundReason.RANGE_TRUNCATED
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
    private record Operand(
            ResolvedUnit unit, Double constant, boolean weak, boolean bareDefault) {

        static Operand typed(ResolvedUnit unit, boolean weak, boolean bareDefault) {
            return new Operand(unit, null, weak, bareDefault);
        }

        static Operand constant(double value) {
            return new Operand(null, value, false, false);
        }

        boolean isConstant() {
            return constant != null;
        }

        /**
         * True when the operand's scale rests on nothing: the kind was the bare money
         * default and no divisor, row label or column header stated a scale. Such a
         * scale is unearned and adopts from the operands that do state one.
         */
        boolean scaleUnstated() {
            return !isConstant() && TypePropagation.scaleUnstated(unit, bareDefault);
        }

        Operand withScale(CellScale scale) {
            return new Operand(
                    ResolvedUnit.of(unit.kind(), scale), constant, weak, bareDefault);
        }
    }

    /**
     * True when a money cell's scale rests on nothing: the kind was the bare money
     * default and no divisor, row label or column header stated a scale. A cell whose
     * kind came from a stated cue (Amount in Rs) keeps its own scale even when it is
     * rupees, so a genuine rupees-against-lakhs conflict is still refused.
     */
    private static boolean scaleUnstated(ResolvedUnit unit, boolean bareDefault) {
        return bareDefault
                && unit.scale() == CellScale.UNIT
                && unit.kind() == CellKind.MONEY;
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
        CellScale typedScale = null;
        double factorConstantMove = 1d;

        for (Operand factor : factors) {
            if (factor.isConstant()) {
                factorConstantMove *= factor.constant();
                continue;
            }
            if (!factor.unit().isResolved()) {
                continue;
            }
            CellKind next = factor.unit().kind();
            // A dimensionless factor carries no scale: percent/ratio must not erase the
            // lakh scale of the money it multiplies.
            if (next != CellKind.PERCENT && next != CellKind.RATIO && typedScale == null) {
                typedScale = factor.unit().scale();
            }
            kind = kind == null ? next : multiply(kind, next);
            if (kind == null) {
                return ResolvedUnit.unresolved();
            }
        }
        // Every constant factor moves the scale together, applied once against the
        // typed base: a leading and a trailing constant must move it the same way.
        // One that lands on none of the named scales (2, 0.5, ...) is an ordinary
        // quantity multiplier, not a unit conversion, so it leaves the scale alone.
        CellScale scale = typedScale == null ? CellScale.UNIT : typedScale;
        CellScale movedByFactors = CellScale.multipliedBy(scale, factorConstantMove);
        scale = movedByFactors == null ? scale : movedByFactors;

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
     *
     * <p>The non-weak members lead: a kind the arithmetic could only guess yields to
     * the members whose kinds were actually known, which is what lets a revenue block
     * of five known money lines outvote one count-times-constants product. Two guards
     * keep that from over-reaching: the non-weak members must agree, and at least one
     * of them must rest on more than the bare money default. When either fails the
     * group falls back to requiring every member to agree, as before.
     */
    private static Map<Long, ResolvedUnit> resolveAggregations(
            CellGraph graph,
            Map<Long, CellTypes.Typed> typed,
            Map<Long, UnboundReason> refusals) {
        Map<Long, ResolvedUnit> units = new LinkedHashMap<>();
        for (Aggregation aggregation : graph.aggregations()) {
            ResolvedUnit strong = consensus(aggregation, typed, row -> !row.weak());
            ResolvedUnit resolved;
            if (strong.refusal() != null) {
                // The members whose kinds were known disagree: a genuine conflict.
                resolved = strong;
            } else if (strong.isResolved() && hasNonDefaultSupporter(aggregation, typed)) {
                resolved = strong;
            } else {
                resolved = consensus(aggregation, typed, row -> true);
            }
            if (resolved.refusal() != null) {
                units.put(aggregation.headCellId(), resolved);
                refusals.putIfAbsent(aggregation.headCellId(), resolved.refusal());
                continue;
            }
            if (!resolved.isResolved()) {
                units.put(aggregation.headCellId(), ResolvedUnit.unresolved());
                continue;
            }
            units.put(aggregation.headCellId(), resolved);
            // A head that could not type on its own still takes its group's unit: the
            // group is what the head is a total of. An errored head has no number, so
            // it stays out of the type table.
            GraphCell head = graph.cells().get(aggregation.headCellId());
            if (head != null && head.numeric()) {
                typed.computeIfAbsent(aggregation.headCellId(),
                        id -> new CellTypes.Typed(
                                resolved, TypeSource.AGGREGATION, 1, false, false));
            }
        }
        return units;
    }

    /** The common unit of the members passing {@code include}, or a refusal. */
    private static ResolvedUnit consensus(
            Aggregation aggregation,
            Map<Long, CellTypes.Typed> typed,
            java.util.function.Predicate<CellTypes.Typed> include) {
        CellKind kind = null;
        CellScale stated = null;
        boolean scaleDisagrees = false;
        for (Aggregation.Member member : aggregation.members()) {
            CellTypes.Typed row = typed.get(member.cellId());
            if (row == null || !include.test(row)) {
                continue;
            }
            if (kind == null) {
                kind = row.unit().kind();
            } else if (kind != row.unit().kind()) {
                return ResolvedUnit.refused(UnboundReason.KIND_CONFLICT);
            }
            // An unearned scale adopts the one the other members state, so a bare
            // literal in a lakh group is lakh rather than a rupees claim.
            if (scaleUnstated(row.unit(), row.bareDefault())) {
                continue;
            }
            if (stated == null) {
                stated = row.unit().scale();
            } else if (stated != row.unit().scale()) {
                scaleDisagrees = true;
            }
        }
        if (kind == null) {
            return ResolvedUnit.unresolved();
        }
        if (scaleDisagrees) {
            return ResolvedUnit.refused(UnboundReason.SCALE_CONFLICT);
        }
        return ResolvedUnit.of(kind, stated == null ? CellScale.UNIT : stated);
    }

    /** True when a non-weak member rests on more than the bare money default. */
    private static boolean hasNonDefaultSupporter(
            Aggregation aggregation, Map<Long, CellTypes.Typed> typed) {
        for (Aggregation.Member member : aggregation.members()) {
            CellTypes.Typed row = typed.get(member.cellId());
            if (row != null && !row.weak() && !row.bareDefault()) {
                return true;
            }
        }
        return false;
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
