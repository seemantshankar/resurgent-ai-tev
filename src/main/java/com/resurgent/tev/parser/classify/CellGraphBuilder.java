package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the {@link CellGraph} for one parse run from persisted cells and the
 * reference edges recorded at ingest. A formula states exactly what it does, so
 * composition, sign and role are read from it rather than inferred from how the
 * sheet looks.
 *
 * <p>Ranges are expanded against persisted cells only — a gap inside
 * {@code SUM(J33:J54)} yields no member rather than an invented blank cell — and a
 * local range with no target worksheet resolves against the sheet the formula is
 * on.
 */
final class CellGraphBuilder {

    /** Max cells examined when expanding one range reference, as annotations use. */
    static final int MAX_RANGE_CELLS = 256;

    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "^\\$?([A-Za-z]+)\\$?(\\d+):\\$?([A-Za-z]+)\\$?(\\d+)$");
    private static final Pattern SINGLE_CELL = Pattern.compile("^\\$?[A-Za-z]{1,3}\\$?\\d{1,7}$");
    private static final Pattern SUM_CALL = Pattern.compile("(?i)^sum\\s*\\((.*)\\)$");
    private static final Pattern NUMERIC_ATOM = Pattern.compile(
            "(?<![A-Za-z$\\d.])\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+|\\^\\s*-?\\d+)?(?![\\d.])");
    private static final Pattern A1_TOKEN = Pattern.compile(
            "\\$?[A-Za-z]{1,3}\\$?\\d{1,7}(?::\\$?[A-Za-z]{1,3}\\$?\\d{1,7})?");

    CellGraph read(WorkspaceRepository repo, long parseRunId) throws SQLException {
        Objects.requireNonNull(repo, "repo");
        List<InterpretationCellView> cells = repo.selectInterpretationCellsForParseRun(parseRunId);
        return build(
                parseRunId,
                cells,
                repo.selectCellReferencesForParseRun(parseRunId),
                repo.selectNumberFormatsForParseRun(parseRunId),
                headerScope(repo, parseRunId, cells));
    }

    /**
     * The Candidate-scoped header lookup the graph uses to type entity rows. It is
     * read from the same discovery Candidates the reporting evidence uses, so the
     * header is bounded to the data block and a note banner cannot pull in a
     * distant schedule's unit (ADR 0020). A run with no Candidates yields no scope.
     */
    private static HeaderScope headerScope(
            WorkspaceRepository repo, long parseRunId, List<InterpretationCellView> cells)
            throws SQLException {
        List<CandidateRow> candidates = repo.selectCandidatesForParseRun(parseRunId);
        if (candidates.isEmpty()) {
            return null;
        }
        Map<Long, Set<Long>> membersByCandidate = InterpretationEvidenceResolver.indexMembers(
                repo.selectCandidateMembersForParseRun(parseRunId));
        Map<Long, CandidateRow> candidatesById = new HashMap<>();
        for (CandidateRow candidate : candidates) {
            candidatesById.put(candidate.candidateId(), candidate);
        }
        return new HeaderScope(
                parseRunId,
                InterpretationEvidenceResolver.indexCells(cells),
                InterpretationEvidenceResolver.indexOwners(candidates, membersByCandidate),
                membersByCandidate,
                candidatesById);
    }

    private record HeaderScope(
            long parseRunId,
            Map<Long, InterpretationCellView> byId,
            Map<Long, List<CandidateRow>> ownersByCell,
            Map<Long, Set<Long>> membersByCandidate,
            Map<Long, CandidateRow> candidatesById) {

        String columnHeader(InterpretationCellView cell) {
            return InterpretationEvidenceResolver.resolvedColumnHeader(
                    parseRunId, cell, byId, ownersByCell, membersByCandidate, candidatesById);
        }
    }

    CellGraph build(
            long parseRunId,
            List<InterpretationCellView> cells,
            List<CellReferenceEdge> edges) {
        return build(parseRunId, cells, edges, Map.of(), null);
    }

    CellGraph build(
            long parseRunId,
            List<InterpretationCellView> cells,
            List<CellReferenceEdge> edges,
            Map<Long, String> numberFormats) {
        return build(parseRunId, cells, edges, numberFormats, null);
    }

    CellGraph build(
            long parseRunId,
            List<InterpretationCellView> cells,
            List<CellReferenceEdge> edges,
            Map<Long, String> numberFormats,
            HeaderScope headerScope) {
        Index index = Index.of(cells, numberFormats == null ? Map.of() : numberFormats, headerScope);
        Map<Long, List<CellReferenceEdge>> edgesByFrom = new HashMap<>();
        for (CellReferenceEdge edge : edges) {
            edgesByFrom.computeIfAbsent(edge.fromCellId(), id -> new ArrayList<>()).add(edge);
        }

        Map<Long, List<CellDependency>> dependencies = new LinkedHashMap<>();
        List<Aggregation> aggregations = new ArrayList<>();
        List<InputCell> inputs = new ArrayList<>();
        Set<Long> readAsSummand = new HashSet<>();
        Set<Long> readAsDriver = new HashSet<>();

        for (GraphCell cell : index.ordered()) {
            if (!cell.numeric() && !cell.isFormula()) {
                continue;
            }
            if (!cell.isFormula()) {
                if (cell.numeric()) {
                    inputs.add(inputOf(cell, index));
                }
                continue;
            }
            List<CellReferenceEdge> cellEdges =
                    edgesByFrom.getOrDefault(cell.cellId(), List.of());
            Parsed parsed = parse(cell, cellEdges, index);
            dependencies.put(cell.cellId(), parsed.dependencies());
            for (CellDependency dependency : parsed.dependencies()) {
                if (dependency.cellId() == null) {
                    continue;
                }
                if (dependency.role().isSummand()) {
                    readAsSummand.add(dependency.cellId());
                } else if (dependency.role().isDriver()) {
                    readAsDriver.add(dependency.cellId());
                }
            }
            // A constant-only formula is an input: it hardcodes a number just as a
            // literal does, and nothing upstream can ever type it. Its own constants
            // are operands, not dependencies on other cells.
            if (cell.numeric() && readsNoCell(parsed.dependencies())) {
                inputs.add(inputOf(cell, index));
            }
            parsed.aggregation().ifPresent(aggregations::add);
        }

        Set<Long> driverOnly = new LinkedHashSet<>(readAsDriver);
        driverOnly.removeAll(readAsSummand);

        Map<Long, List<Aggregation>> memberships = new LinkedHashMap<>();
        for (Aggregation aggregation : aggregations) {
            for (Aggregation.Member member : aggregation.members()) {
                memberships
                        .computeIfAbsent(member.cellId(), id -> new ArrayList<>())
                        .add(aggregation);
            }
        }

        return new CellGraph(
                parseRunId,
                index.byId(),
                inputs,
                aggregations,
                dependencies,
                driverOnly,
                memberships);
    }

    /** True when a formula reads no other cell and hits no barrier: it hardcodes its number. */
    private static boolean readsNoCell(List<CellDependency> dependencies) {
        for (CellDependency dependency : dependencies) {
            if (dependency.cellId() != null || dependency.isBarrier()) {
                return false;
            }
        }
        return true;
    }

    /**
     * An input decision, keyed so a repeated row-series collapses to one. The key is
     * the sheet, the row label and the relative formula shape, which is what makes a
     * ten-year row one judgment rather than ten.
     */
    private static InputCell inputOf(GraphCell cell, Index index) {
        String label = cell.rowLabel() == null ? "" : cell.rowLabel();
        String shape = cell.isFormula()
                ? relativeSignature(cell.formulaText(), cell)
                : "literal";
        String key = cell.worksheetId() + "|" + OntologySlice.normalize(label) + "|" + shape;
        return new InputCell(cell.cellId(), cell.rowLabel(), key);
    }

    private record Parsed(List<CellDependency> dependencies, Optional<Aggregation> aggregation) {}

    /**
     * Read one formula's structure: its operands with the role each operator gives
     * them, and the aggregation it heads when it sums or nets two or more terms.
     */
    private Parsed parse(GraphCell cell, List<CellReferenceEdge> edges, Index index) {
        String expr = stripLeadingEquals(cell.formulaText());
        List<Term> terms = topLevelTerms(expr);
        List<EdgePlacement> placements = placeEdges(expr, edges);

        List<CellDependency> dependencies = new ArrayList<>();
        List<Aggregation.Member> members = new ArrayList<>();

        for (Term term : terms) {
            List<EdgePlacement> inTerm = new ArrayList<>();
            for (EdgePlacement placement : placements) {
                if (placement.start() >= term.start() && placement.start() < term.end()) {
                    inTerm.add(placement);
                }
            }
            TermShape shape = shapeOf(term.text());
            for (NumericAtom atom : topLevelConstants(term, shape)) {
                dependencies.add(CellDependency.constant(
                        constantRole(shape, term, atom), atom.value()));
            }
            for (EdgePlacement placement : inTerm) {
                CellReferenceEdge edge = placement.edge();
                DependencyRole role = roleFor(shape, term, placement, expr);
                UnboundReason barrier = barrierOf(edge);
                if (barrier != null) {
                    dependencies.add(CellDependency.barrier(role, barrier));
                    continue;
                }
                RangeExpansion expansion = resolve(edge, cell, index);
                if (expansion.truncated()) {
                    // A range too large to scan in full is not complete evidence: it
                    // poisons the formula instead of typing from a partial member list.
                    dependencies.add(CellDependency.barrier(role, UnboundReason.RANGE_TRUNCATED));
                    continue;
                }
                List<Long> targets = expansion.cellIds();
                if (targets.isEmpty()) {
                    // A reference that resolves to no persisted cell is a blank
                    // coordinate, not a broken link: it contributes nothing.
                    continue;
                }
                for (long target : targets) {
                    dependencies.add(CellDependency.of(target, role));
                    if (role.isSummand()) {
                        members.add(new Aggregation.Member(
                                target,
                                role == DependencyRole.SUMMAND_PLUS,
                                index.byId().containsKey(target)
                                        ? index.byId().get(target).rowLabel()
                                        : null));
                    }
                }
            }
        }

        // A head sums or nets two or more things: either several top-level terms, or
        // one SUM whose range covers more than one persisted cell. One member is a
        // pass-through, not a group.
        Optional<Aggregation> aggregation = Optional.empty();
        List<Aggregation.Member> uniqueMembers = dedupeMembers(members);
        if (uniqueMembers.size() > 1) {
            aggregation = Optional.of(new Aggregation(
                    cell.cellId(),
                    cell.worksheetId(),
                    relativeSignature(cell.formulaText(), cell),
                    cell.rowLabel(),
                    uniqueMembers));
        }
        return new Parsed(List.copyOf(dependencies), aggregation);
    }

    /** A cell named twice in one head counts once, keeping the first sign it was given. */
    private static List<Aggregation.Member> dedupeMembers(List<Aggregation.Member> members) {
        Map<Long, Aggregation.Member> byCell = new LinkedHashMap<>();
        for (Aggregation.Member member : members) {
            byCell.putIfAbsent(member.cellId(), member);
        }
        return List.copyOf(byCell.values());
    }

    private static UnboundReason barrierOf(CellReferenceEdge edge) {
        if ("external".equals(edge.refKind()) || edge.externalLinkId() != null
                || (edge.rawToken() != null && edge.rawToken().contains("["))) {
            return UnboundReason.EXTERNAL_DEPENDENCY;
        }
        if (edge.unresolvedReason() != null && !edge.unresolvedReason().isBlank()) {
            return UnboundReason.BROKEN_DEPENDENCY;
        }
        if ("defined_name".equals(edge.refKind())) {
            return UnboundReason.BROKEN_DEPENDENCY;
        }
        return null;
    }

    private static DependencyRole roleFor(
            TermShape shape, Term term, EdgePlacement placement, String expr) {
        return switch (shape) {
            case BARE_REFERENCE, SUM_CALL ->
                    term.plus() ? DependencyRole.SUMMAND_PLUS : DependencyRole.SUMMAND_MINUS;
            case MULTIPLICATIVE -> afterTopLevelDivide(term, placement)
                    ? DependencyRole.DIVISOR
                    : DependencyRole.FACTOR;
            case OTHER -> DependencyRole.OTHER;
        };
    }

    /** True when the operand sits to the right of a top-level {@code /} in its term. */
    private static boolean afterTopLevelDivide(Term term, EdgePlacement placement) {
        int depth = 0;
        boolean inQuotes = false;
        boolean divided = false;
        for (int i = term.start(); i < placement.start() && i < term.end(); i++) {
            char c = term.source().charAt(i);
            if (c == '"' || c == '\'') {
                inQuotes = !inQuotes;
            } else if (inQuotes) {
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '/' && depth == 0) {
                divided = true;
            } else if (c == '*' && depth == 0) {
                divided = false;
            }
        }
        return divided;
    }

    /**
     * The hardcoded numbers in a multiplicative term. Only these matter to scale: a
     * literal added to a sum changes the amount, a literal dividing it changes the
     * unit the amount is expressed in. A power such as {@code 10^5} or {@code 1E-5}
     * is one atom, not its separate digits, so {@code /10^5} moves one divisor, not
     * two stray ones. Roles stay positional downstream via {@link NumericAtom#start}.
     */
    private static List<NumericAtom> topLevelConstants(Term term, TermShape shape) {
        if (shape != TermShape.MULTIPLICATIVE) {
            return List.of();
        }
        List<NumericAtom> constants = new ArrayList<>();
        Matcher matcher = NUMERIC_ATOM.matcher(term.text());
        while (matcher.find()) {
            double value;
            try {
                value = evaluateAtom(matcher.group());
            } catch (NumberFormatException e) {
                // Not a number after all; the operand contributes nothing.
                continue;
            }
            if (!Double.isFinite(value)) {
                continue;
            }
            constants.add(new NumericAtom(value, matcher.start()));
        }
        return List.copyOf(constants);
    }

    private record NumericAtom(double value, int start) {}

    /**
     * One atom's value: {@code 10^5} evaluates to 100000, plain and
     * scientific-notation numerals parse directly.
     */
    private static double evaluateAtom(String atom) {
        int caret = atom.indexOf('^');
        if (caret < 0) {
            return Double.parseDouble(atom);
        }
        double base = Double.parseDouble(atom.substring(0, caret).trim());
        double exponent = Double.parseDouble(atom.substring(caret + 1).trim());
        return Math.pow(base, exponent);
    }

    /** A constant to the right of a top-level {@code /} divides; anything else scales. */
    private static DependencyRole constantRole(TermShape shape, Term term, NumericAtom atom) {
        if (shape != TermShape.MULTIPLICATIVE) {
            return DependencyRole.OTHER;
        }
        int depth = 0;
        boolean inQuotes = false;
        boolean divided = false;
        String text = term.text();
        for (int i = 0; i < atom.start() && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                inQuotes = !inQuotes;
            } else if (inQuotes) {
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '/' && depth == 0) {
                divided = true;
            } else if (c == '*' && depth == 0) {
                divided = false;
            }
        }
        return divided ? DependencyRole.DIVISOR : DependencyRole.FACTOR;
    }

    private enum TermShape { BARE_REFERENCE, SUM_CALL, MULTIPLICATIVE, OTHER }

    private static TermShape shapeOf(String term) {
        String trimmed = term.trim();
        if (trimmed.isEmpty()) {
            return TermShape.OTHER;
        }
        String unwrapped = unwrapParens(trimmed);
        if (hasTopLevelOperator(unwrapped, "*/")) {
            return TermShape.MULTIPLICATIVE;
        }
        if (SINGLE_CELL.matcher(stripSheetPrefix(unwrapped)).matches()) {
            return TermShape.BARE_REFERENCE;
        }
        Matcher sum = SUM_CALL.matcher(unwrapped);
        if (sum.matches() && balanced(sum.group(1))) {
            return TermShape.SUM_CALL;
        }
        return TermShape.OTHER;
    }

    private static String unwrapParens(String text) {
        String current = text;
        while (current.length() > 2 && current.charAt(0) == '('
                && current.charAt(current.length() - 1) == ')'
                && balanced(current.substring(1, current.length() - 1))) {
            current = current.substring(1, current.length() - 1).trim();
        }
        return current;
    }

    private static boolean balanced(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private static boolean hasTopLevelOperator(String text, String operators) {
        int depth = 0;
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                inQuotes = !inQuotes;
            } else if (inQuotes) {
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && operators.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String stripSheetPrefix(String token) {
        int bang = token.lastIndexOf('!');
        return bang < 0 ? token : token.substring(bang + 1);
    }

    private static String stripLeadingEquals(String formula) {
        String text = formula == null ? "" : formula.trim();
        return text.startsWith("=") ? text.substring(1).trim() : text;
    }

    /** One top-level additive term, with its sign and its span in the source expression. */
    private record Term(String source, int start, int end, boolean plus) {
        String text() {
            return source.substring(start, end);
        }
    }

    /**
     * Split an expression on its top-level {@code +} and {@code -}, ignoring
     * operators inside parentheses, quotes, or a sign position (a leading minus, or
     * the exponent of {@code 1E-5}).
     */
    static List<Term> topLevelTerms(String expr) {
        List<Term> terms = new ArrayList<>();
        int depth = 0;
        boolean inQuotes = false;
        boolean plus = true;
        int start = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '\'' || c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (inQuotes) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (c == '+' || c == '-') && !isSignPosition(expr, i)) {
                if (i > start) {
                    terms.add(new Term(expr, start, i, plus));
                }
                plus = c == '+';
                start = i + 1;
            }
        }
        if (start < expr.length()) {
            terms.add(new Term(expr, start, expr.length(), plus));
        }
        return terms;
    }

    /** A {@code +}/{@code -} that signs a value rather than joining two terms. */
    private static boolean isSignPosition(String expr, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == 'e' || c == 'E') {
                // Exponent only when what precedes it is a digit: 1E-5, not SOME-5.
                for (int j = i - 1; j >= 0; j--) {
                    char prev = expr.charAt(j);
                    if (Character.isWhitespace(prev)) {
                        continue;
                    }
                    return Character.isDigit(prev);
                }
                return false;
            }
            return !(Character.isLetterOrDigit(c) || c == ')' || c == '$' || c == '%');
        }
        return true;
    }

    /** One reference edge located at a character offset in the formula. */
    private record EdgePlacement(CellReferenceEdge edge, int start) {}

    /**
     * Locate each edge in the formula text so operands can be attributed to the term
     * that contains them. Edges are persisted in token order, so a forward-only
     * cursor keeps repeated tokens apart.
     */
    private static List<EdgePlacement> placeEdges(String expr, List<CellReferenceEdge> edges) {
        List<CellReferenceEdge> ordered = new ArrayList<>(edges);
        ordered.sort(Comparator.comparingInt(CellReferenceEdge::tokenIndex));
        String haystack = expr.toLowerCase(Locale.ROOT);
        List<EdgePlacement> placements = new ArrayList<>();
        int cursor = 0;
        for (CellReferenceEdge edge : ordered) {
            int at = indexOfToken(haystack, edge, cursor);
            if (at < 0) {
                at = indexOfToken(haystack, edge, 0);
            }
            if (at < 0) {
                continue;
            }
            placements.add(new EdgePlacement(edge, at));
            cursor = at + 1;
        }
        return placements;
    }

    private static int indexOfToken(String haystack, CellReferenceEdge edge, int from) {
        for (String candidate : List.of(
                edge.rawToken() == null ? "" : edge.rawToken(),
                edge.targetRange() == null ? "" : edge.targetRange())) {
            if (candidate.isBlank()) {
                continue;
            }
            int at = haystack.indexOf(candidate.toLowerCase(Locale.ROOT), from);
            if (at >= 0) {
                return at;
            }
        }
        return -1;
    }

    /**
     * The persisted cells one edge points at: the resolved cell, a single coordinate,
     * or every persisted cell inside a range. Never invents a cell for a gap, and
     * resolves a local range against the formula's own worksheet when the edge
     * carries no target worksheet.
     */
    /** A range expansion, and whether the cell-scan cap cut it short. */
    private record RangeExpansion(List<Long> cellIds, boolean truncated) {
        static RangeExpansion of(List<Long> cellIds) {
            return new RangeExpansion(cellIds, false);
        }
    }

    private static RangeExpansion resolve(CellReferenceEdge edge, GraphCell from, Index index) {
        long worksheetId = edge.targetWorksheetId() == null
                ? from.worksheetId()
                : edge.targetWorksheetId();
        String range = edge.targetRange();
        if (range != null && range.contains(":")) {
            if (edge.isWholeColumn() || edge.isWholeRow()) {
                return RangeExpansion.of(List.of());
            }
            return expandRange(range, worksheetId, index);
        }
        if (edge.resolvedCellId() != null && index.byId().containsKey(edge.resolvedCellId())) {
            return RangeExpansion.of(List.of(edge.resolvedCellId()));
        }
        if (range == null) {
            return RangeExpansion.of(List.of());
        }
        Long cellId = index.at(worksheetId, range);
        return RangeExpansion.of(cellId == null ? List.of() : List.of(cellId));
    }

    private static RangeExpansion expandRange(String range, long worksheetId, Index index) {
        Matcher matcher = RANGE_PATTERN.matcher(stripSheetPrefix(range.trim()));
        if (!matcher.matches()) {
            return RangeExpansion.of(List.of());
        }
        int firstCol = columnNumber(matcher.group(1));
        int firstRow = Integer.parseInt(matcher.group(2));
        int lastCol = columnNumber(matcher.group(3));
        int lastRow = Integer.parseInt(matcher.group(4));
        int minRow = Math.min(firstRow, lastRow);
        int maxRow = Math.max(firstRow, lastRow);
        int minCol = Math.min(firstCol, lastCol);
        int maxCol = Math.max(firstCol, lastCol);

        List<Long> members = new ArrayList<>();
        int examined = 0;
        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                if (++examined > MAX_RANGE_CELLS) {
                    // The rectangle outgrew the scan cap: the members found so far are
                    // not the whole range, so they must never pass as complete evidence.
                    return new RangeExpansion(List.copyOf(members), true);
                }
                Long cellId = index.at(worksheetId, row, col);
                if (cellId != null) {
                    members.add(cellId);
                }
            }
        }
        return RangeExpansion.of(List.copyOf(members));
    }

    private static int columnNumber(String letters) {
        int n = 0;
        for (int i = 0; i < letters.length(); i++) {
            n = n * 26 + (Character.toUpperCase(letters.charAt(i)) - 'A' + 1);
        }
        return n;
    }

    /**
     * The formula rewritten with every A1 reference made relative to the cell it sits
     * on. Two cells of one period series share this shape exactly, which is what
     * identifies a series without needing a header row at all.
     */
    static String relativeSignature(String formulaText, GraphCell cell) {
        String expr = stripLeadingEquals(formulaText);
        Matcher matcher = A1_TOKEN.matcher(expr);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(expr, last, matcher.start());
            out.append(toR1C1(matcher.group(), cell));
            last = matcher.end();
        }
        out.append(expr.substring(last));
        return out.toString().toUpperCase(Locale.ROOT);
    }

    private static String toR1C1(String token, GraphCell cell) {
        String[] parts = token.split(":");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(relativeRef(parts[i], cell));
        }
        return out.toString();
    }

    private static String relativeRef(String ref, GraphCell cell) {
        Matcher matcher = Pattern.compile("^(\\$?)([A-Za-z]{1,3})(\\$?)(\\d{1,7})$").matcher(ref);
        if (!matcher.matches()) {
            return ref;
        }
        boolean absCol = "$".equals(matcher.group(1));
        boolean absRow = "$".equals(matcher.group(3));
        int col = columnNumber(matcher.group(2));
        int row = Integer.parseInt(matcher.group(4));
        String rowPart = absRow ? "R" + row : offset("R", row - cell.rowNum());
        String colPart = absCol ? "C" + col : offset("C", col - cell.colNum());
        return rowPart + colPart;
    }

    private static String offset(String prefix, int delta) {
        return delta == 0 ? prefix : prefix + "[" + delta + "]";
    }

    /** Cell lookups the builder needs: by id, by coordinate, and by row/column. */
    private record Index(
            Map<Long, GraphCell> byId,
            Map<Long, Map<String, Long>> byCoord,
            Map<Long, Map<Long, Long>> byRowCol,
            List<GraphCell> ordered) {

        static Index of(List<InterpretationCellView> cells) {
            return of(cells, Map.of(), null);
        }

        static Index of(List<InterpretationCellView> cells, Map<Long, String> numberFormats) {
            return of(cells, numberFormats, null);
        }

        static Index of(
                List<InterpretationCellView> cells,
                Map<Long, String> numberFormats,
                HeaderScope headerScope) {
            Map<Long, GraphCell> byId = new LinkedHashMap<>();
            Map<Long, Map<String, Long>> byCoord = new HashMap<>();
            Map<Long, Map<Long, Long>> byRowCol = new HashMap<>();
            Map<Long, Map<Long, InterpretationCellView>> rowIndex = new HashMap<>();
            for (InterpretationCellView cell : cells) {
                rowIndex
                        .computeIfAbsent(cell.worksheetId(), id -> new HashMap<>())
                        .put(key(cell.rowNum(), cell.colNum()), cell);
            }
            List<GraphCell> ordered = new ArrayList<>();
            for (InterpretationCellView cell : cells) {
                String rowLabel = nearestRowLabel(cell, rowIndex);
                // The column header is only resolved where it decides anything: an
                // entity row that states no unit of its own. Cost of a scoped lookup is
                // paid for those cells, not for the whole workbook.
                String columnLabel = null;
                if (headerScope != null && LayerBAmountSupport.rowLabelNeedsColumnUnit(rowLabel)) {
                    columnLabel = headerScope.columnHeader(cell);
                }
                GraphCell graphCell = new GraphCell(
                        cell.cellId(),
                        cell.worksheetId(),
                        cell.coord(),
                        cell.rowNum(),
                        cell.colNum(),
                        cell.formulaText(),
                        isNumeric(cell),
                        cell.isError(),
                        rowLabel,
                        columnLabel,
                        cell.displayValue(),
                        cell.numericValue(),
                        cell.valueType(),
                        numberFormats.get(cell.cellId()));
                byId.put(cell.cellId(), graphCell);
                ordered.add(graphCell);
                byCoord
                        .computeIfAbsent(cell.worksheetId(), id -> new HashMap<>())
                        .put(cell.coord() == null ? "" : cell.coord().toUpperCase(Locale.ROOT),
                                cell.cellId());
                byRowCol
                        .computeIfAbsent(cell.worksheetId(), id -> new HashMap<>())
                        .put(key(cell.rowNum(), cell.colNum()), cell.cellId());
            }
            return new Index(byId, byCoord, byRowCol, List.copyOf(ordered));
        }

        Long at(long worksheetId, String coord) {
            Map<String, Long> sheet = byCoord.get(worksheetId);
            if (sheet == null || coord == null) {
                return null;
            }
            return sheet.get(stripSheetPrefix(coord).replace("$", "").toUpperCase(Locale.ROOT));
        }

        Long at(long worksheetId, int rowNum, int colNum) {
            Map<Long, Long> sheet = byRowCol.get(worksheetId);
            return sheet == null ? null : sheet.get(key(rowNum, colNum));
        }

        private static long key(int rowNum, int colNum) {
            return (((long) rowNum) << 32) | (colNum & 0xffffffffL);
        }

        /**
         * A numeric cell: a number, or a formula that is not plainly textual. A
         * formula whose cache is missing still computes a number — an unevaluated
         * workbook must not drop out of the graph — so only a cached non-numeric
         * value rules one out.
         */
        private static boolean isNumeric(InterpretationCellView cell) {
            if (cell.isError()) {
                return false;
            }
            if (cell.numericValue() != null && !cell.numericValue().isBlank()) {
                return true;
            }
            if ("number".equals(cell.valueType())) {
                return true;
            }
            boolean formula = cell.formulaText() != null && !cell.formulaText().isBlank();
            String cached = cell.cachedValue();
            if (cached == null || cached.isBlank()) {
                return formula;
            }
            try {
                Double.parseDouble(cached.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /**
         * The nearest label to the left on the same row, with no coordinate fallback:
         * a coordinate is not a name, and feeding one into path resolution is how
         * {@code J45} came to be bound to a Civil Works asset.
         */
        private static String nearestRowLabel(
                InterpretationCellView cell,
                Map<Long, Map<Long, InterpretationCellView>> rowIndex) {
            Map<Long, InterpretationCellView> sheet =
                    rowIndex.getOrDefault(cell.worksheetId(), Map.of());
            for (int col = cell.colNum() - 1; col >= 1; col--) {
                InterpretationCellView candidate = sheet.get(key(cell.rowNum(), col));
                if (candidate == null) {
                    continue;
                }
                String label = LabelText.of(
                        candidate.textValue(),
                        candidate.displayValue(),
                        candidate.numericValue(),
                        candidate.valueType(),
                        candidate.formulaText());
                if (label != null) {
                    return label;
                }
            }
            return LabelText.of(
                    cell.textValue(),
                    cell.displayValue(),
                    cell.numericValue(),
                    cell.valueType(),
                    cell.formulaText());
        }
    }
}
