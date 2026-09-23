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
        statesDivisorScale.clear();
        Map<Long, CellTypes.Typed> typed = new LinkedHashMap<>();
        Map<Long, UnboundReason> refusals = new LinkedHashMap<>();
        Map<Long, CellScale> blockScale = blockScales(graph);
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
                    new InputTyping.Reading(ResolvedUnit.unresolved(), false, ScaleProvenance.UNSTATED);
            for (InputTyping.Reading candidate : own.values()) {
                if (!fallback.unit().isResolved()) {
                    fallback = candidate;
                    continue;
                }
                if (fallback.unit().kind() != candidate.unit().kind()
                        || fallback.unit().scale() != candidate.unit().scale()) {
                    fallback = new InputTyping.Reading(
                            ResolvedUnit.unresolved(), false, ScaleProvenance.UNSTATED);
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
                    typed.put(cell.cellId(), inBlock(blockScale, cell, new CellTypes.Typed(
                            reading.unit(), TypeSource.INPUT_LABEL, 0,
                            false, reading.bareDefault(), reading.scaleProvenance())));
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
                if (inputIds.contains(cellId)) {
                    continue;
                }
                // A scale refusal is not final: a later pass can inherit the block
                // banner onto an operand and the sum then agrees. Any other refusal
                // still ends the cell.
                UnboundReason already = refusals.get(cellId);
                if (already != null && already != UnboundReason.SCALE_CONFLICT) {
                    continue;
                }
                // A formula is re-derived every pass rather than frozen the first time it
                // yields something. An operand that is still pending is skipped, so an
                // early reading is a partial one; when the operand later types or refuses
                // the formula must be revisited, or a subset guess (percent from the
                // percent factor alone, money from the money factor alone) would be
                // written and never corrected.
                // A bare literal adopts the scale an additive consumer states, and that
                // adoption is written onto the operand's own type so it reaches the
                // binder and the rollup rather than only this formula's local reading.
                // It runs before derive so the formula sees the adopted scale below.
                if (adoptSummandScales(graph, cellId, typed, refusals)) {
                    changed = true;
                }
                Outcome outcome = derive(graph, cellId, typed, refusals);
                if (outcome.unit() != null) {
                    CellTypes.Typed next = inBlock(blockScale, cell, new CellTypes.Typed(
                            outcome.unit(), TypeSource.PROPAGATED, outcome.depth(),
                            outcome.weak(), outcome.bareDefault(), outcome.scaleProvenance()));
                    CellTypes.Typed current = typed.get(cellId);
                    boolean healed = refusals.remove(cellId) != null;
                    if (healed
                            || current == null
                            || !current.unit().equals(next.unit())
                            || current.depth() != next.depth()
                            || current.weak() != next.weak()
                            || current.bareDefault() != next.bareDefault()
                            || current.scaleProvenance() != next.scaleProvenance()) {
                        typed.put(cellId, next);
                        changed = true;
                    }
                } else if (outcome.refusal() != null) {
                    typed.remove(cellId);
                    if (!outcome.refusal().equals(refusals.get(cellId))) {
                        refusals.put(cellId, outcome.refusal());
                        changed = true;
                    }
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

    /**
     * The unit of each numeric cell from the table frame. A banner on its own row
     * ({@code (Rs. in Lacs)}) covers every amount below it until the next banner.
     * A unit phrase inside a header row ({@code Amount in Rs} beside {@code Floor}
     * and {@code Rate}) covers only that column. The formula explains the number.
     */
    private static Map<Long, CellScale> blockScales(CellGraph graph) {
        Set<Long> rowsWithAmount = new HashSet<>();
        Map<Long, Integer> textOnRow = new HashMap<>();
        for (GraphCell cell : graph.cells().values()) {
            long rowKey = cell.worksheetId() * 1_000_000L + cell.rowNum();
            if (cell.numeric()) {
                rowsWithAmount.add(rowKey);
            } else if (cell.displayValue() != null && !cell.displayValue().isBlank()) {
                textOnRow.merge(rowKey, 1, Integer::sum);
            }
        }
        Map<Long, List<Banner>> banners = new HashMap<>();
        for (GraphCell cell : graph.cells().values()) {
            if (cell.numeric() || cell.isFormula()) {
                continue;
            }
            long rowKey = cell.worksheetId() * 1_000_000L + cell.rowNum();
            if (rowsWithAmount.contains(rowKey)) {
                continue;
            }
            CellScale scale = CellScale.blockBanner(cell.displayValue());
            if (scale == null) {
                continue;
            }
            boolean columnOnly = textOnRow.getOrDefault(rowKey, 0) >= 3;
            banners.computeIfAbsent(cell.worksheetId(), id -> new ArrayList<>())
                    .add(new Banner(cell.rowNum(), cell.colNum(), scale, columnOnly));
        }
        Map<Long, CellScale> inherited = new HashMap<>();
        for (GraphCell cell : graph.cells().values()) {
            if (!cell.numeric()) {
                continue;
            }
            Banner block = null;
            Banner column = null;
            for (Banner banner : banners.getOrDefault(cell.worksheetId(), List.of())) {
                if (banner.row() >= cell.rowNum()) {
                    continue;
                }
                if (banner.columnOnly()) {
                    if (banner.col() == cell.colNum()
                            && (column == null || banner.row() > column.row())) {
                        column = banner;
                    }
                } else if (block == null || banner.row() > block.row()) {
                    block = banner;
                }
            }
            Banner governing = column != null ? column : block;
            if (governing != null) {
                inherited.put(cell.cellId(), governing.scale());
            }
        }
        return inherited;
    }

    /** A money amount takes its block's banner. A rate, a count, or a percent does not. */
    private static CellTypes.Typed inBlock(
            Map<Long, CellScale> blockScale, GraphCell cell, CellTypes.Typed typed) {
        CellScale banner = blockScale.get(cell.cellId());
        if (banner == null || typed.unit().kind() != CellKind.MONEY) {
            return typed;
        }
        if (typed.unit().scale() == banner && typed.scaleProvenance() == ScaleProvenance.STATED) {
            return typed;
        }
        return new CellTypes.Typed(
                ResolvedUnit.of(CellKind.MONEY, banner),
                typed.typeSource(),
                typed.depth(),
                typed.weak(),
                false,
                ScaleProvenance.STATED);
    }

    private record Banner(int row, int col, CellScale scale, boolean columnOnly) {}

    private record Outcome(
            ResolvedUnit unit,
            UnboundReason refusal,
            int depth,
            Strength strength,
            ScaleProvenance scaleProvenance) {

        static Outcome typed(
                ResolvedUnit unit, int depth, Strength strength, ScaleProvenance scaleProvenance) {
            return new Outcome(unit, null, depth, strength, scaleProvenance);
        }

        static Outcome refused(UnboundReason refusal) {
            return new Outcome(null, refusal, 0, Strength.strong(), ScaleProvenance.UNSTATED);
        }

        static Outcome pending() {
            return new Outcome(null, null, 0, Strength.strong(), ScaleProvenance.UNSTATED);
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
     * One formula cell's unit from its operands. A barrier on a factor or divisor is
     * fatal: a product is never typed from a subset of its factors. A kind-conflict
     * summand is not: a sum takes the kind its typed members already agree on, the
     * same rule an aggregation uses, so one refused member does not poison every
     * total downstream. A blank or untyped summand contributes nothing. Any other
     * barrier (a broken link, an external workbook, a scale conflict, a cycle, a
     * truncated range) still refuses the formula.
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
        GraphCell cell = graph.cells().get(cellId);

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
                    if (dependency.role().isSummand() && refused == UnboundReason.KIND_CONFLICT) {
                        continue;
                    }
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
                            operand.unit(), operand.weak(), operand.bareDefault(),
                            operand.scaleProvenance()));
        }

        // Adoption is not local to this formula. A bare literal's scale is written
        // onto the operand's own type by adoptSummandScales before this runs, so the
        // operand below already carries the scale an additive consumer stated. That
        // is what makes the reading reach the binder and the rollup, and what keeps
        // an adopted scale from having to be re-guessed here.

        ScaleProvenance summandProvenance = provenanceOf(cell, summands);
        ScaleProvenance productProvenance = provenanceOf(cell, factors, divisors);
        ScaleProvenance otherProvenance = provenanceOf(cell, others);

        if (!summands.isEmpty()) {
            ResolvedUnit sum = combineSummands(summands);
            if (sum.refusal() != null) {
                return Outcome.refused(sum.refusal());
            }
            if (sum.isResolved()) {
                return Outcome.typed(sum, depth, strengthOf(summands, false), summandProvenance);
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
                                Strength.strong(),
                                productProvenance);
                    }
                }
                return Outcome.typed(product, depth, strength, productProvenance);
            }
        }
        if (!others.isEmpty()) {
            // Another function or a comparison: the operands still carry a dimension,
            // but only when they all agree on one.
            ResolvedUnit combined = combineSummands(others);
            if (combined.isResolved()) {
                return Outcome.typed(
                        combined, depth, strengthOf(others, false), otherProvenance);
            }
        }
        return pending ? Outcome.pending() : Outcome.refused(UnboundReason.UNTYPABLE);
    }

    /**
     * Whether a Cell's own formula states a scale in a divisor, memoised for this run.
     *
     * <p>A Cell's formula text does not change while types propagate, but {@code derive}
     * asks three times per Cell and the pass loop runs up to {@link #MAX_PASSES} times,
     * so the underlying scan was repeated thousands of times per Cell for one fixed
     * answer.
     */
    private final Map<Long, Boolean> statesDivisorScale = new HashMap<>();

    private boolean statesDivisorScale(GraphCell cell) {
        if (cell == null) {
            return false;
        }
        Boolean cached = statesDivisorScale.get(cell.cellId());
        if (cached != null) {
            return cached;
        }
        boolean stated =
                InterpretationEvidenceResolver.formulaDivisorScale(cell.formulaText()) != null;
        statesDivisorScale.put(cell.cellId(), stated);
        return stated;
    }

    /**
     * A scale's provenance for an expression: stated when this cell's own formula
     * states a scale in a divisor ({@code /10^5} → lakh, {@code 8000*300/100000} → lakh),
     * otherwise the strongest provenance among the typed operands. Constants carry no
     * provenance; an expression over only constants has no evidence either.
     */
    private ScaleProvenance provenanceOf(GraphCell cell, List<Operand>... buckets) {
        if (statesDivisorScale(cell)) {
            return ScaleProvenance.STATED;
        }
        ScaleProvenance provenance = ScaleProvenance.UNSTATED;
        for (List<Operand> bucket : buckets) {
            for (Operand operand : bucket) {
                if (operand.isConstant()) {
                    continue;
                }
                provenance = ScaleProvenance.strongest(provenance, operand.provenance());
            }
        }
        return provenance;
    }

    /**
     * Writes onto an unstated operand's own type the one scale its additive consumer
     * states. Restricted to {@code +} and {@code -}: every member of an additive group
     * shares the group's scale, so {@code K54 = I54 - J54} proves J54 is lakh when I54
     * is, and {@code J47 = SUM(J9:J39)} proves its unstated members are lakh once J47
     * has one. A product or quotient carries no such proof — {@code F159 = D159*E159}
     * moves a money scale onto a quantity and a rate — so those buckets are never
     * adopted.
     *
     * <p>The fixpoint guard is in what may be a source: only a <em>claimed</em> scale
     * (stated by a cue, or adopted from one), never an unstated default, so a chain of
     * unstated cells can never invent a scale between them. Every adopted scale
     * therefore traces back to a cue. An adopted cell may source a further adoption,
     * which is what lets a scale converge outward; the chain is monotone (only
     * {@code UNSTATED} operands are candidates, and a scale is never overwritten), so
     * it terminates rather than oscillating. Adoption waits while any summand is still
     * pending, so it cannot settle on a source that later turns out to disagree.
     *
     * @return true when at least one operand's type changed
     */
    private static boolean adoptSummandScales(
            CellGraph graph,
            long headCellId,
            Map<Long, CellTypes.Typed> typed,
            Map<Long, UnboundReason> refusals) {
        // A head's own claimed scale is the group's scale — it may have been adopted
        // from a consumer's formula, as J47 was from K47 = I47 - J47 — so it can hand
        // that scale to the members that state none.
        CellTypes.Typed head = typed.get(headCellId);
        CellScale stated = head != null && head.scaleProvenance().isClaimed()
                ? head.unit().scale()
                : null;
        List<Long> candidates = new ArrayList<>();
        for (CellDependency dependency : graph.dependenciesOf(headCellId)) {
            if (dependency.isConstant() || dependency.isBarrier()) {
                continue;
            }
            if (!dependency.role().isSummand()) {
                continue;
            }
            Long operandId = dependency.cellId();
            if (operandId == null) {
                continue;
            }
            CellTypes.Typed row = typed.get(operandId);
            if (row == null) {
                if (!refusals.containsKey(operandId)) {
                    // Still pending: a scale stated later would be adopted from a
                    // different source, so wait rather than settle on this one.
                    return false;
                }
                continue;
            }
            if (!row.unit().isMoney()) {
                continue;
            }
            if (!row.scaleProvenance().isClaimed()) {
                candidates.add(operandId);
                continue;
            }
            if (stated == null) {
                stated = row.unit().scale();
            } else if (stated != row.unit().scale()) {
                // Two claimed scales disagree: a genuine conflict, not an adoption.
                return false;
            }
        }
        if (stated == null || candidates.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Long operandId : candidates) {
            CellTypes.Typed row = typed.get(operandId);
            if (row == null || row.scaleProvenance().isClaimed()) {
                continue;
            }
            typed.put(operandId, new CellTypes.Typed(
                    ResolvedUnit.of(row.unit().kind(), stated),
                    row.typeSource(),
                    row.depth(),
                    row.weak(),
                    row.bareDefault(),
                    ScaleProvenance.ADOPTED));
            changed = true;
        }
        return changed;
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
            ResolvedUnit unit,
            Double constant,
            boolean weak,
            boolean bareDefault,
            ScaleProvenance provenance) {

        static Operand typed(
                ResolvedUnit unit,
                boolean weak,
                boolean bareDefault,
                ScaleProvenance provenance) {
            return new Operand(unit, null, weak, bareDefault, provenance);
        }

        static Operand constant(double value) {
            return new Operand(null, value, false, false, ScaleProvenance.UNSTATED);
        }

        boolean isConstant() {
            return constant != null;
        }
    }

    /**
     * A sum takes its members' unit, and only when the members that state one agree. A
     * member whose scale is unstated adopts the stated one rather than forcing a
     * conflict: it is an additive sibling, so it shares the group's scale by
     * construction. The same rule as {@link #consensus}, so a formula and an
     * aggregation over the same cells cannot disagree about their unit.
     */
    private static ResolvedUnit combineSummands(List<Operand> operands) {
        CellKind kind = null;
        CellScale stated = null;
        boolean scaleDisagrees = false;
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
            } else if (kind != unit.kind()) {
                return ResolvedUnit.refused(UnboundReason.KIND_CONFLICT);
            }
            if (!operand.provenance().isClaimed()) {
                continue;
            }
            if (stated == null) {
                stated = unit.scale();
            } else if (stated != unit.scale()) {
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

    /**
     * Dimensional arithmetic over a product or quotient: quantity times rate is
     * money, money over money is a ratio, money over quantity is a rate, and a
     * constant divisor moves the scale — money divided by 100,000 is money in lakhs.
     */
    private static ResolvedUnit combineProduct(List<Operand> factors, List<Operand> divisors) {
        CellKind kind = null;
        CellScale typedScale = null;
        List<Double> factorConstants = new ArrayList<>();

        for (Operand factor : factors) {
            if (factor.isConstant()) {
                factorConstants.add(factor.constant());
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
        // Each literal is tried on its own. A scale step (100000, 0.00001) moves the
        // unit. A rate (0.005) is not a step, so it must not be folded into the step
        // first: 100000*0.005 is 500, and 500 is not a unit. Order does not matter
        // because a non-step leaves the scale where the last step put it.
        CellScale scale = typedScale == null ? CellScale.UNIT : typedScale;
        for (double factor : factorConstants) {
            CellScale moved = CellScale.multipliedBy(scale, factor);
            if (moved != null) {
                scale = moved;
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
            Consensus strong = consensus(aggregation, typed, row -> !row.weak());
            Consensus chosen;
            if (strong.unit().refusal() != null) {
                // The members whose kinds were known disagree: a genuine conflict.
                chosen = strong;
            } else if (strong.unit().isResolved() && hasNonDefaultSupporter(aggregation, typed)) {
                chosen = strong;
            } else {
                chosen = consensus(aggregation, typed, row -> true);
            }
            ResolvedUnit resolved = chosen.unit();
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
                                resolved, TypeSource.AGGREGATION, 1, false, false,
                                chosen.provenance()));
            }
        }
        return units;
    }

    /** An aggregation's unit and the trust its scale carries. */
    private record Consensus(ResolvedUnit unit, ScaleProvenance provenance) {}

    /** The common unit of the members passing {@code include}, or a refusal. */
    private static Consensus consensus(
            Aggregation aggregation,
            Map<Long, CellTypes.Typed> typed,
            java.util.function.Predicate<CellTypes.Typed> include) {
        CellKind kind = null;
        CellScale stated = null;
        ScaleProvenance provenance = ScaleProvenance.UNSTATED;
        boolean scaleDisagrees = false;
        for (Aggregation.Member member : aggregation.members()) {
            CellTypes.Typed row = typed.get(member.cellId());
            if (row == null || !include.test(row)) {
                continue;
            }
            if (kind == null) {
                kind = row.unit().kind();
            } else if (kind != row.unit().kind()) {
                return new Consensus(
                        ResolvedUnit.refused(UnboundReason.KIND_CONFLICT), ScaleProvenance.UNSTATED);
            }
            // An unstated scale adopts the one the other members state, so a bare
            // literal in a lakh group is lakh rather than a rupees claim. A scale that
            // was itself adopted does not become a source here either.
            if (!row.scaleProvenance().isClaimed()) {
                continue;
            }
            if (stated == null) {
                stated = row.unit().scale();
            } else if (stated != row.unit().scale()) {
                scaleDisagrees = true;
            }
            provenance = ScaleProvenance.strongest(provenance, row.scaleProvenance());
        }
        if (kind == null) {
            return new Consensus(ResolvedUnit.unresolved(), ScaleProvenance.UNSTATED);
        }
        if (scaleDisagrees) {
            return new Consensus(
                    ResolvedUnit.refused(UnboundReason.SCALE_CONFLICT), ScaleProvenance.UNSTATED);
        }
        return new Consensus(
                ResolvedUnit.of(kind, stated == null ? CellScale.UNIT : stated), provenance);
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
