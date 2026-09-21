package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Candidate- and merge-scoped header / comparison-context evidence
 * for Cell interpretations (#117). No LLM; does not invent calendar years or INR.
 */
final class InterpretationEvidenceResolver {

    private static final Pattern YEAR_RELATIVE =
            Pattern.compile("(?i)\\b(?:year|yr|y)\\s*([0-9]{1,2})\\b");
    private static final Pattern YEAR_CALENDAR =
            Pattern.compile("(?i)\\b((?:19|20)\\d{2})\\b");
    private static final Pattern BASIS =
            Pattern.compile("(?i)\\b(projected|projection|actual|budget|forecast|historical)\\b");
    /** Division by INR/display-unit magnitudes (e.g. Om Arham {@code /10^5} → lakh). */
    private static final Pattern FORMULA_DIVISOR_BILLION =
            Pattern.compile("(?i)/\\s*(?:10\\s*\\^\\s*9|10\\s*\\*\\*\\s*9|1e9|1000000000)\\b");
    private static final Pattern FORMULA_DIVISOR_CRORE =
            Pattern.compile("(?i)/\\s*(?:10\\s*\\^\\s*7|10\\s*\\*\\*\\s*7|1e7|10000000)\\b");
    private static final Pattern FORMULA_DIVISOR_MILLION =
            Pattern.compile("(?i)/\\s*(?:10\\s*\\^\\s*6|10\\s*\\*\\*\\s*6|1e6|1000000)\\b");
    private static final Pattern FORMULA_DIVISOR_LAKH =
            Pattern.compile("(?i)/\\s*(?:10\\s*\\^\\s*5|10\\s*\\*\\*\\s*5|1e5|100000)\\b");
    private static final Pattern FORMULA_DIVISOR_THOUSAND =
            Pattern.compile("(?i)/\\s*(?:10\\s*\\^\\s*3|10\\s*\\*\\*\\s*3|1e3|1000)\\b");

    /** One formula divisor rule: the pattern, the scale it implies, and its rule id. */
    private record DivisorRule(Pattern pattern, CellScale scale, String normalized, String ruleId) {}

    // Largest magnitude first so /1000000000 is not misread as /1000. The single
    // source of truth for the scale a formula's own divisor implies, shared by the
    // reporting evidence and by input typing.
    private static final List<DivisorRule> FORMULA_DIVISORS = List.of(
            new DivisorRule(FORMULA_DIVISOR_BILLION, CellScale.BILLION, "billion", "formula_divisor_1e9"),
            new DivisorRule(FORMULA_DIVISOR_CRORE, CellScale.CRORE, "crore", "formula_divisor_1e7"),
            new DivisorRule(FORMULA_DIVISOR_MILLION, CellScale.MILLION, "million", "formula_divisor_1e6"),
            new DivisorRule(FORMULA_DIVISOR_LAKH, CellScale.LAKH, "lakh", "formula_divisor_1e5"),
            new DivisorRule(FORMULA_DIVISOR_THOUSAND, CellScale.THOUSAND, "thousand", "formula_divisor_1e3"));

    /**
     * The scale a formula's own divisor implies ({@code /10^5} → lakh), or
     * {@code null}. A constant-only formula such as {@code 8000*300/100000} hardcodes
     * a lakh figure; typing it from labels alone loses that. Shared with the
     * reporting evidence so the two never diverge.
     */
    static CellScale formulaDivisorScale(String formulaText) {
        if (formulaText == null || formulaText.isBlank()) {
            return null;
        }
        for (DivisorRule rule : FORMULA_DIVISORS) {
            if (rule.pattern().matcher(formulaText).find()) {
                return rule.scale();
            }
        }
        return null;
    }

    private InterpretationEvidenceResolver() {}

    static List<InterpretationEvidence> resolve(
            long parseRunId,
            InterpretationCellView target,
            Map<Long, InterpretationCellView> byId,
            Map<Long, List<CandidateRow>> ownersByCell,
            Map<Long, Set<Long>> membersByCandidate,
            Map<Long, CandidateRow> candidatesById,
            NomenclatureBinding binding) {
        return resolve(
                parseRunId,
                target,
                new ResolveCache(byId),
                ownersByCell,
                membersByCandidate,
                candidatesById,
                binding);
    }

    /**
     * Hot-path entry point. Callers that resolve every Cell in a parse run pass one
     * {@link ResolveCache} so Candidate scopes and merged anchors are derived once per
     * Candidate instead of once per Cell.
     */
    static List<InterpretationEvidence> resolve(
            long parseRunId,
            InterpretationCellView target,
            ResolveCache cache,
            Map<Long, List<CandidateRow>> ownersByCell,
            Map<Long, Set<Long>> membersByCandidate,
            Map<Long, CandidateRow> candidatesById,
            NomenclatureBinding binding) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(candidatesById, "candidatesById");

        List<CandidateRow> peers = ownershipPeers(target, ownersByCell);
        if (peers.isEmpty() && binding != null) {
            CandidateRow bound = candidatesById.get(binding.candidateId());
            if (bound != null) {
                peers = List.of(bound);
            }
        }
        if (peers.isEmpty()) {
            return missingHeaders(parseRunId, target.cellId());
        }

        List<InterpretationEvidence> out = resolveWithPeers(
                parseRunId, target, cache, membersByCandidate, candidatesById, peers);
        // Formula-/context-bound amounts may sit outside every narrow owner's members;
        // if ownership scope yields no headers, try the binding Candidate's Packet scope.
        if (binding != null && headersAllMissing(out)) {
            CandidateRow bound = candidatesById.get(binding.candidateId());
            if (bound != null
                    && peers.stream().noneMatch(p -> p.candidateId() == bound.candidateId())) {
                out = resolveWithPeers(
                        parseRunId,
                        target,
                        cache,
                        membersByCandidate,
                        candidatesById,
                        List.of(bound));
            }
        }
        // Discovery can segment a column-header band into its own child Candidate while
        // the data rows form another, so no narrow owner spans the header row. The
        // coverage parent does; retry with it as an additional scope before reporting
        // missing column headers.
        if (columnHeadersAllMissing(out)) {
            CandidateRow parent = coverageParentOwner(target, ownersByCell);
            if (parent != null
                    && peers.stream().noneMatch(p -> p.candidateId() == parent.candidateId())) {
                List<CandidateRow> withParent = new ArrayList<>(peers);
                withParent.add(parent);
                out = resolveWithPeers(
                        parseRunId,
                        target,
                        cache,
                        membersByCandidate,
                        candidatesById,
                        withParent);
            }
        }
        return List.copyOf(out);
    }

    private static List<InterpretationEvidence> resolveWithPeers(
            long parseRunId,
            InterpretationCellView target,
            ResolveCache cache,
            Map<Long, Set<Long>> membersByCandidate,
            Map<Long, CandidateRow> candidatesById,
            List<CandidateRow> peers) {
        List<HeaderChain> rowChains = new ArrayList<>();
        List<HeaderChain> colChains = new ArrayList<>();
        for (CandidateRow scope : peers) {
            ScopeIndex scopeIndex = cache.scope(scope, membersByCandidate, candidatesById);
            InterpretationCellView focus = focusCell(target, cache, scopeIndex);
            rowChains.add(rowHeaderChain(focus, scopeIndex));
            colChains.add(columnHeaderChain(focus, scopeIndex));
        }
        List<InterpretationEvidence> out = new ArrayList<>();
        out.addAll(mergeHeaderRole(
                parseRunId, target.cellId(), EvidenceRole.ROW_HEADER, rowChains));
        out.addAll(mergeHeaderRole(
                parseRunId, target.cellId(), EvidenceRole.COLUMN_HEADER, colChains));
        out.addAll(contextEvidence(parseRunId, target.cellId(), out, target));
        return out;
    }

    private static boolean headersAllMissing(List<InterpretationEvidence> evidence) {
        boolean sawHeader = false;
        for (InterpretationEvidence item : evidence) {
            if (EvidenceRole.ROW_HEADER.equals(item.role())
                    || EvidenceRole.COLUMN_HEADER.equals(item.role())) {
                sawHeader = true;
                if (!EvidenceResolution.MISSING.equals(item.resolution())) {
                    return false;
                }
            }
        }
        return sawHeader;
    }

    private static boolean columnHeadersAllMissing(List<InterpretationEvidence> evidence) {
        boolean sawColumnHeader = false;
        for (InterpretationEvidence item : evidence) {
            if (EvidenceRole.COLUMN_HEADER.equals(item.role())) {
                sawColumnHeader = true;
                if (!EvidenceResolution.MISSING.equals(item.resolution())) {
                    return false;
                }
            }
        }
        return sawColumnHeader;
    }

    private static CandidateRow coverageParentOwner(
            InterpretationCellView target, Map<Long, List<CandidateRow>> ownersByCell) {
        for (CandidateRow owner : ownersByCell.getOrDefault(target.cellId(), List.of())) {
            if ("coverage_parent".equals(owner.candidateKind())) {
                return owner;
            }
        }
        return null;
    }

    private static List<CandidateRow> ownershipPeers(
            InterpretationCellView target, Map<Long, List<CandidateRow>> ownersByCell) {
        List<CandidateRow> owners = ownersByCell.getOrDefault(target.cellId(), List.of());
        List<CandidateRow> narrow = owners.stream()
                .filter(c -> !"coverage_parent".equals(c.candidateKind()))
                .toList();
        List<CandidateRow> primaryPool = narrow.isEmpty() ? owners : narrow;
        if (primaryPool.isEmpty()) {
            return List.of();
        }
        CandidateRow primary = pickNarrowest(primaryPool);
        int primaryArea = bboxArea(primary);
        return primaryPool.stream()
                .filter(c -> bboxArea(c) <= primaryArea)
                .toList();
    }

    /**
     * Candidate members plus Packet-style inherited header/label context from the parent
     * (or worksheet). Label context is limited to the immediate left column so adjacent
     * parallel schedules do not leak.
     */
    private static Set<Long> expandedScope(
            CandidateRow candidate,
            Map<Long, Set<Long>> membersByCandidate,
            ResolveCache cache,
            Map<Long, CandidateRow> candidatesById) {
        Set<Long> scope = new HashSet<>(
                membersByCandidate.getOrDefault(candidate.candidateId(), Set.of()));
        if ("coverage_parent".equals(candidate.candidateKind())) {
            return scope;
        }
        Integer minRow = candidate.bboxMinRow();
        Integer minCol = candidate.bboxMinCol();
        Integer maxCol = candidate.bboxMaxCol();
        Integer maxRow = candidate.bboxMaxRow();
        if (minRow == null || minCol == null || maxCol == null) {
            return scope;
        }
        Set<Long> poolIds;
        if (candidate.parentCandidateId() != null
                && membersByCandidate.containsKey(candidate.parentCandidateId())) {
            poolIds = membersByCandidate.get(candidate.parentCandidateId());
        } else {
            poolIds = cache.worksheetCellIds(candidate.worksheetId());
        }
        for (Long id : poolIds) {
            InterpretationCellView view = cache.byId().get(id);
            if (view == null || scope.contains(id)) {
                continue;
            }
            // Immediate left column only — prevents parallel-schedule label leak.
            boolean labelCol = maxRow != null
                    && view.colNum() == minCol - 1
                    && view.rowNum() >= minRow
                    && view.rowNum() <= maxRow;
            if (labelCol) {
                scope.add(id);
            }
        }
        // Column headers: nearest contiguous header band above the Candidate, not every
        // earlier coverage-parent cell (stacked schedules must not leak upper headers).
        for (InterpretationCellView view :
                nearestHeaderBand(
                        cache.byId(), poolIds, candidate.worksheetId(), minRow, minCol, maxCol)) {
            scope.add(view.cellId());
        }
        return scope;
    }

    /**
     * Walk upward from {@code minRow - 1}: keep header-candidate rows in the column band;
     * stop at a body/numeric row or at a blank gap after the band has started.
     */
    private static List<InterpretationCellView> nearestHeaderBand(
            Map<Long, InterpretationCellView> byId,
            Set<Long> poolIds,
            long worksheetId,
            int minRow,
            int minCol,
            int maxCol) {
        Map<Integer, List<InterpretationCellView>> byRow = new HashMap<>();
        for (Long id : poolIds) {
            InterpretationCellView view = byId.get(id);
            if (view == null || view.worksheetId() != worksheetId) {
                continue;
            }
            if (view.rowNum() >= minRow || view.colNum() < minCol || view.colNum() > maxCol) {
                continue;
            }
            byRow.computeIfAbsent(view.rowNum(), r -> new ArrayList<>()).add(view);
        }
        List<InterpretationCellView> band = new ArrayList<>();
        boolean started = false;
        for (int row = minRow - 1; row >= 1; row--) {
            List<InterpretationCellView> cells = byRow.getOrDefault(row, List.of());
            boolean headerish = false;
            for (InterpretationCellView cell : cells) {
                if (isColumnHeaderCandidate(cell)) {
                    headerish = true;
                    break;
                }
            }
            if (headerish) {
                for (InterpretationCellView cell : cells) {
                    if (isColumnHeaderCandidate(cell)) {
                        band.add(cell);
                    }
                }
                started = true;
                continue;
            }
            if (started) {
                break;
            }
            // Not started: skip body/blank rows above the Candidate until local headers.
        }
        return band;
    }

    private static CandidateRow pickNarrowest(List<CandidateRow> owners) {
        return owners.stream()
                .min(Comparator
                        .comparing((CandidateRow c) -> "coverage_parent".equals(c.candidateKind()))
                        .thenComparingInt(InterpretationEvidenceResolver::bboxArea)
                        .thenComparingLong(CandidateRow::candidateId))
                .orElseThrow();
    }

    private static int bboxArea(CandidateRow c) {
        Integer minR = c.bboxMinRow();
        Integer maxR = c.bboxMaxRow();
        Integer minC = c.bboxMinCol();
        Integer maxC = c.bboxMaxCol();
        if (minR == null || maxR == null || minC == null || maxC == null) {
            return Integer.MAX_VALUE;
        }
        return (maxR - minR + 1) * (maxC - minC + 1);
    }

    private static InterpretationCellView focusCell(
            InterpretationCellView target, ResolveCache cache, ScopeIndex scope) {
        if (!target.isMergedParticipant() || target.mergedRange() == null) {
            return target;
        }
        // Prefer the merged anchor inside the same Candidate scope when present.
        for (InterpretationCellView cell :
                cache.mergedAnchors(target.worksheetId(), target.mergedRange())) {
            if (scope.ids().contains(cell.cellId())) {
                return cell;
            }
        }
        return target;
    }

    private static HeaderChain rowHeaderChain(InterpretationCellView focus, ScopeIndex scope) {
        List<InterpretationCellView> left = new ArrayList<>();
        for (InterpretationCellView cell : scope.row(focus.worksheetId(), focus.rowNum())) {
            if (cell.colNum() >= focus.colNum()) {
                continue;
            }
            if (labelText(cell) == null) {
                continue;
            }
            left.add(cell);
        }
        left.sort(Comparator.comparingInt(InterpretationCellView::colNum));
        return HeaderChain.from(left);
    }

    private static HeaderChain columnHeaderChain(InterpretationCellView focus, ScopeIndex scope) {
        // Nearest header band above focus: skip body/numeric rows until the first header,
        // then stop at the first gap (stacked upper schedules stay out).
        Map<Integer, List<InterpretationCellView>> byRow = new HashMap<>();
        for (InterpretationCellView cell : scope.column(focus.worksheetId(), focus.colNum())) {
            if (cell.rowNum() >= focus.rowNum()) {
                continue;
            }
            if (!isColumnHeaderCandidate(cell)) {
                continue;
            }
            byRow.computeIfAbsent(cell.rowNum(), r -> new ArrayList<>()).add(cell);
        }
        List<InterpretationCellView> band = new ArrayList<>();
        boolean started = false;
        for (int row = focus.rowNum() - 1; row >= 1; row--) {
            List<InterpretationCellView> atRow = byRow.get(row);
            if (atRow != null && !atRow.isEmpty()) {
                band.addAll(0, atRow);
                started = true;
                continue;
            }
            if (started) {
                break;
            }
            // Not started: keep scanning through body rows toward the local header.
        }
        return HeaderChain.from(band);
    }

    /**
     * Text labels and structural numeric/date headers (e.g. calendar year columns).
     */
    private static boolean isColumnHeaderCandidate(InterpretationCellView cell) {
        if (labelText(cell) != null) {
            return true;
        }
        if (cell.dateValue() != null && !cell.dateValue().isBlank()) {
            return true;
        }
        String numeric = cell.numericValue();
        if (numeric == null || numeric.isBlank()) {
            return false;
        }
        try {
            double value = Double.parseDouble(numeric);
            int year = (int) value;
            return year == value && year >= 1900 && year <= 2100;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static List<InterpretationEvidence> mergeHeaderRole(
            long parseRunId,
            long cellId,
            String role,
            List<HeaderChain> chains) {
        List<HeaderChain> nonempty = chains.stream().filter(c -> !c.cells().isEmpty()).toList();
        if (nonempty.isEmpty()) {
            return List.of(missing(parseRunId, cellId, role, 0));
        }
        HeaderChain first = nonempty.get(0);
        boolean agree = nonempty.stream().allMatch(c -> c.signature().equals(first.signature()));
        if (agree) {
            return resolvedChain(parseRunId, cellId, role, first);
        }
        // Conflicting Candidate scopes → ambiguous alternatives, no chosen normalized value.
        List<InterpretationEvidence> ambiguous = new ArrayList<>();
        int ordinal = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (HeaderChain chain : nonempty) {
            for (InterpretationCellView cell : chain.cells()) {
                String text = headerDisplay(cell);
                String key = cell.cellId() + "|" + text;
                if (!seen.add(key)) {
                    continue;
                }
                ambiguous.add(new InterpretationEvidence(
                        parseRunId,
                        cellId,
                        role,
                        cell.cellId(),
                        text,
                        ordinal++,
                        EvidenceResolution.AMBIGUOUS,
                        null,
                        "candidate_overlap_conflict"));
            }
        }
        return ambiguous;
    }

    private static List<InterpretationEvidence> resolvedChain(
            long parseRunId, long cellId, String role, HeaderChain chain) {
        List<InterpretationEvidence> rows = new ArrayList<>();
        int ordinal = 0;
        for (InterpretationCellView cell : chain.cells()) {
            rows.add(new InterpretationEvidence(
                    parseRunId,
                    cellId,
                    role,
                    cell.cellId(),
                    headerDisplay(cell),
                    ordinal++,
                    EvidenceResolution.RESOLVED,
                    null,
                    "nearest_in_scope"));
        }
        return rows;
    }

    private static List<InterpretationEvidence> contextEvidence(
            long parseRunId,
            long cellId,
            List<InterpretationEvidence> headers,
            InterpretationCellView target) {
        Map<String, List<Cue>> cues = new LinkedHashMap<>();
        cues.put(EvidenceRole.PERIOD, new ArrayList<>());
        cues.put(EvidenceRole.BASIS, new ArrayList<>());
        cues.put(EvidenceRole.CURRENCY, new ArrayList<>());
        cues.put(EvidenceRole.SCALE, new ArrayList<>());
        cues.put(EvidenceRole.UNIT, new ArrayList<>());

        for (InterpretationEvidence header : headers) {
            if (!EvidenceResolution.RESOLVED.equals(header.resolution())
                    && !EvidenceResolution.AMBIGUOUS.equals(header.resolution())) {
                continue;
            }
            String text = header.sourceText() == null ? "" : header.sourceText();
            collectCues(cues, header.sourceCellId(), text);
        }
        collectFormulaScaleCues(cues, target);

        List<InterpretationEvidence> out = new ArrayList<>();
        for (Map.Entry<String, List<Cue>> entry : cues.entrySet()) {
            List<Cue> found = entry.getValue();
            if (found.isEmpty()) {
                continue;
            }
            Set<String> distinct = new LinkedHashSet<>();
            for (Cue cue : found) {
                distinct.add(cueIdentity(cue));
            }
            if (distinct.size() == 1) {
                Cue cue = preferredCue(found);
                out.add(new InterpretationEvidence(
                        parseRunId,
                        cellId,
                        entry.getKey(),
                        cue.sourceCellId(),
                        cue.sourceText(),
                        0,
                        EvidenceResolution.RESOLVED,
                        cue.normalizedValue(),
                        cue.ruleId()));
            } else {
                int ordinal = 0;
                for (Cue cue : found) {
                    out.add(new InterpretationEvidence(
                            parseRunId,
                            cellId,
                            entry.getKey(),
                            cue.sourceCellId(),
                            cue.sourceText(),
                            ordinal++,
                            EvidenceResolution.AMBIGUOUS,
                            null,
                            "context_conflict"));
                }
            }
        }
        return out;
    }

    /** Prefer normalized value when present so header "Lacs" and {@code /10^5} can agree. */
    private static String cueIdentity(Cue cue) {
        if (cue.normalizedValue() != null && !cue.normalizedValue().isBlank()) {
            return cue.normalizedValue().toLowerCase(Locale.ROOT);
        }
        return cue.sourceText() == null ? "" : cue.sourceText().toLowerCase(Locale.ROOT);
    }

    private static Cue preferredCue(List<Cue> found) {
        for (Cue cue : found) {
            if (cue.ruleId() != null && cue.ruleId().startsWith("formula_divisor_")) {
                return cue;
            }
        }
        return found.get(0);
    }

    private static void collectFormulaScaleCues(
            Map<String, List<Cue>> cues, InterpretationCellView target) {
        if (target == null || target.formulaText() == null || target.formulaText().isBlank()) {
            return;
        }
        String formula = target.formulaText();
        for (DivisorRule rule : FORMULA_DIVISORS) {
            if (matchDivisor(cues, target, formula, rule)) {
                return;
            }
        }
    }

    private static boolean matchDivisor(
            Map<String, List<Cue>> cues,
            InterpretationCellView target,
            String formula,
            DivisorRule rule) {
        Matcher matcher = rule.pattern().matcher(formula);
        if (!matcher.find()) {
            return false;
        }
        cues.get(EvidenceRole.SCALE)
                .add(new Cue(
                        target.cellId(),
                        matcher.group().replaceAll("\\s+", ""),
                        rule.normalized(),
                        rule.ruleId()));
        return true;
    }

    private static void collectCues(Map<String, List<Cue>> cues, Long sourceCellId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher relative = YEAR_RELATIVE.matcher(text);
        if (relative.find()) {
            // Preserve relative period; do not invent a calendar year.
            cues.get(EvidenceRole.PERIOD)
                    .add(new Cue(sourceCellId, relative.group().trim(), null, "relative_period"));
        } else {
            Matcher calendar = YEAR_CALENDAR.matcher(text);
            if (calendar.find()) {
                cues.get(EvidenceRole.PERIOD)
                        .add(new Cue(
                                sourceCellId,
                                calendar.group(1),
                                calendar.group(1),
                                "calendar_year_header"));
            }
        }
        Matcher basis = BASIS.matcher(text);
        if (basis.find()) {
            cues.get(EvidenceRole.BASIS)
                    .add(new Cue(sourceCellId, basis.group().trim(), null, "basis_token"));
        }
        Matcher currency = KindTokens.CURRENCY.matcher(text);
        if (currency.find()) {
            String token = currency.group().trim();
            String normalized = KindTokens.normalizeCurrency(token);
            cues.get(EvidenceRole.CURRENCY)
                    .add(new Cue(sourceCellId, token, normalized, "currency_token"));
        }
        Matcher scale = CellScale.SCALE_TOKEN.matcher(text);
        if (scale.find()) {
            String token = scale.group().trim();
            cues.get(EvidenceRole.SCALE)
                    .add(new Cue(sourceCellId, token, normalizeScale(token), "scale_token"));
        }
        Matcher unit = KindTokens.UNIT.matcher(text);
        if (unit.find()) {
            String token = unit.group().trim();
            cues.get(EvidenceRole.UNIT)
                    .add(new Cue(sourceCellId, token, KindTokens.normalizeUnit(token), "unit_token"));
        }
    }

    /** Canonical scale key so plural spelling variants agree on one conflict key. */
    private static String normalizeScale(String token) {
        CellScale scale = CellScale.ofToken(token);
        return switch (scale) {
            case LAKH -> "lakh";
            case CRORE -> "crore";
            case THOUSAND -> "thousand";
            case MILLION -> "million";
            case BILLION -> "billion";
            case UNIT -> token.toLowerCase(Locale.ROOT);
        };
    }

    private static List<InterpretationEvidence> missingHeaders(long parseRunId, long cellId) {
        return List.of(
                missing(parseRunId, cellId, EvidenceRole.ROW_HEADER, 0),
                missing(parseRunId, cellId, EvidenceRole.COLUMN_HEADER, 0));
    }

    private static InterpretationEvidence missing(
            long parseRunId, long cellId, String role, int ordinal) {
        return new InterpretationEvidence(
                parseRunId,
                cellId,
                role,
                null,
                null,
                ordinal,
                EvidenceResolution.MISSING,
                null,
                null);
    }

    static String labelText(InterpretationCellView cell) {
        if (cell == null) {
            return null;
        }
        if (cell.textValue() != null && !cell.textValue().isBlank()
                && (cell.numericValue() == null || cell.numericValue().isBlank())) {
            return cell.textValue().trim();
        }
        if (cell.displayValue() != null && !cell.displayValue().isBlank()
                && !"number".equals(cell.valueType())
                && (cell.formulaText() == null || cell.formulaText().isBlank())) {
            return cell.displayValue().trim();
        }
        return null;
    }

    private static String headerDisplay(InterpretationCellView cell) {
        String label = labelText(cell);
        if (label != null) {
            return label;
        }
        if (cell.dateValue() != null && !cell.dateValue().isBlank()) {
            return cell.dateValue().trim();
        }
        if (cell.numericValue() != null && !cell.numericValue().isBlank()) {
            try {
                double value = Double.parseDouble(cell.numericValue());
                int year = (int) value;
                if (year == value) {
                    return Integer.toString(year);
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
            return cell.numericValue().trim();
        }
        return null;
    }

    private record Cue(Long sourceCellId, String sourceText, String normalizedValue, String ruleId) {}

    private record HeaderChain(List<InterpretationCellView> cells, String signature) {
        static HeaderChain from(List<InterpretationCellView> cells) {
            StringBuilder sig = new StringBuilder();
            for (InterpretationCellView cell : cells) {
                sig.append(cell.cellId()).append(':').append(headerDisplay(cell)).append('|');
            }
            return new HeaderChain(List.copyOf(cells), sig.toString());
        }
    }

    /** Build ownership indexes used by {@link InterpretationWriter}. */
    static Map<Long, List<CandidateRow>> indexOwners(
            List<CandidateRow> candidates, Map<Long, Set<Long>> membersByCandidate) {
        Map<Long, List<CandidateRow>> owners = new HashMap<>();
        for (CandidateRow candidate : candidates) {
            for (Long cellId :
                    membersByCandidate.getOrDefault(candidate.candidateId(), Set.of())) {
                owners.computeIfAbsent(cellId, id -> new ArrayList<>()).add(candidate);
            }
        }
        return owners;
    }

    static Map<Long, Set<Long>> indexMembers(List<long[]> pairs) {
        Map<Long, Set<Long>> byCandidate = new HashMap<>();
        for (long[] pair : pairs) {
            byCandidate.computeIfAbsent(pair[0], id -> new HashSet<>()).add(pair[1]);
        }
        return byCandidate;
    }

    static Map<Long, InterpretationCellView> indexCells(List<InterpretationCellView> cells) {
        Map<Long, InterpretationCellView> byId = new HashMap<>();
        for (InterpretationCellView cell : cells) {
            byId.put(cell.cellId(), cell);
        }
        return byId;
    }

    /**
     * The Candidate-scoped resolved column-header text for typing, or {@code null}
     * when the header is missing or the scopes disagree. This reuses the exact
     * scoping the reporting evidence uses, so a note banner cannot be mistaken for a
     * distant block's header (ADR 0020).
     */
    static String resolvedColumnHeader(
            long parseRunId,
            InterpretationCellView target,
            ResolveCache cache,
            Map<Long, List<CandidateRow>> ownersByCell,
            Map<Long, Set<Long>> membersByCandidate,
            Map<Long, CandidateRow> candidatesById) {
        List<InterpretationEvidence> evidence = resolve(
                parseRunId, target, cache, ownersByCell, membersByCandidate, candidatesById, null);
        StringBuilder header = new StringBuilder();
        for (InterpretationEvidence item : evidence) {
            if (!EvidenceRole.COLUMN_HEADER.equals(item.role())) {
                continue;
            }
            if (EvidenceResolution.AMBIGUOUS.equals(item.resolution())) {
                return null;
            }
            if (EvidenceResolution.RESOLVED.equals(item.resolution())
                    && item.sourceText() != null
                    && !item.sourceText().isBlank()) {
                if (header.length() > 0) {
                    header.append(' ');
                }
                header.append(item.sourceText().trim());
            }
        }
        return header.length() == 0 ? null : header.toString();
    }

    /**
     * A Candidate's expanded scope, with its Cells bucketed by row and by column so a
     * header chain is a map lookup rather than a scan over every member per target Cell.
     */
    private record ScopeIndex(
            Set<Long> ids,
            Map<AxisKey, List<InterpretationCellView>> byRow,
            Map<AxisKey, List<InterpretationCellView>> byCol) {

        List<InterpretationCellView> row(long worksheetId, int rowNum) {
            return byRow.getOrDefault(new AxisKey(worksheetId, rowNum), List.of());
        }

        List<InterpretationCellView> column(long worksheetId, int colNum) {
            return byCol.getOrDefault(new AxisKey(worksheetId, colNum), List.of());
        }

        static ScopeIndex of(Set<Long> ids, Map<Long, InterpretationCellView> byId) {
            Map<AxisKey, List<InterpretationCellView>> byRow = new HashMap<>();
            Map<AxisKey, List<InterpretationCellView>> byCol = new HashMap<>();
            for (Long id : ids) {
                InterpretationCellView cell = byId.get(id);
                if (cell == null) {
                    continue;
                }
                byRow.computeIfAbsent(
                                new AxisKey(cell.worksheetId(), cell.rowNum()),
                                k -> new ArrayList<>())
                        .add(cell);
                byCol.computeIfAbsent(
                                new AxisKey(cell.worksheetId(), cell.colNum()),
                                k -> new ArrayList<>())
                        .add(cell);
            }
            return new ScopeIndex(Set.copyOf(ids), byRow, byCol);
        }
    }

    private record AxisKey(long worksheetId, int index) {}

    private record MergeKey(long worksheetId, String mergedRange) {}

    /**
     * Per-parse-run memo shared across every {@link #resolve} call. Candidate scopes, the
     * per-worksheet Cell pool and merged anchors depend only on the run and not on the
     * target Cell, so deriving them once per Candidate keeps resolution linear in Cell
     * count instead of quadratic.
     */
    static final class ResolveCache {

        private final Map<Long, InterpretationCellView> byId;
        private final Map<Long, ScopeIndex> scopeByCandidate = new HashMap<>();
        private final Map<Long, Set<Long>> cellIdsByWorksheet = new HashMap<>();
        private Map<MergeKey, List<InterpretationCellView>> anchorsByMerge;

        ResolveCache(Map<Long, InterpretationCellView> byId) {
            this.byId = Objects.requireNonNull(byId, "byId");
        }

        Map<Long, InterpretationCellView> byId() {
            return byId;
        }

        ScopeIndex scope(
                CandidateRow candidate,
                Map<Long, Set<Long>> membersByCandidate,
                Map<Long, CandidateRow> candidatesById) {
            ScopeIndex cached = scopeByCandidate.get(candidate.candidateId());
            if (cached != null) {
                return cached;
            }
            ScopeIndex built = ScopeIndex.of(
                    expandedScope(candidate, membersByCandidate, this, candidatesById), byId);
            scopeByCandidate.put(candidate.candidateId(), built);
            return built;
        }

        Set<Long> worksheetCellIds(long worksheetId) {
            return cellIdsByWorksheet.computeIfAbsent(worksheetId, ws -> {
                Set<Long> ids = new HashSet<>();
                for (InterpretationCellView cell : byId.values()) {
                    if (cell.worksheetId() == ws) {
                        ids.add(cell.cellId());
                    }
                }
                return ids;
            });
        }

        List<InterpretationCellView> mergedAnchors(long worksheetId, String mergedRange) {
            if (anchorsByMerge == null) {
                anchorsByMerge = new HashMap<>();
                for (InterpretationCellView cell : byId.values()) {
                    if (!cell.isMergedAnchor() || cell.mergedRange() == null) {
                        continue;
                    }
                    anchorsByMerge
                            .computeIfAbsent(
                                    new MergeKey(cell.worksheetId(), cell.mergedRange()),
                                    k -> new ArrayList<>())
                            .add(cell);
                }
            }
            return anchorsByMerge.getOrDefault(new MergeKey(worksheetId, mergedRange), List.of());
        }
    }
}
