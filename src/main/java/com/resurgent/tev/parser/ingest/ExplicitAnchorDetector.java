package com.resurgent.tev.parser.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.resurgent.tev.parser.db.Jsonb;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds explainable cost-head candidates from labelled totals, unlabeled
 * structural SUM formulas, and leaf-sum fallbacks.
 */
final class ExplicitAnchorDetector {

    static final String EXPLICIT = "explicit_total_anchor";
    static final String STRUCTURAL = "structural_total";
    static final String LEAF_SUM = "leaf_sum";
    static final String SUBTOTAL = "SUBTOTAL";
    static final String MERGED_PARTICIPANT = "MERGED_PARTICIPANT";
    static final String ERROR = "ERROR";
    static final String SCRATCH = "SCRATCH";
    static final String HEADER = "HEADER";
    static final String TOTAL_ANCHOR = "TOTAL_ANCHOR";
    static final String NOT_AMOUNT = "NOT_AMOUNT";
    static final String PERIOD = "PERIOD";
    static final String UNIT = "UNIT";
    static final String CURRENCY = "CURRENCY";

    private static final Pattern TOTAL_LABEL = Pattern.compile(
            "(?i)^(grand\\s+)?total(\\s+(project\\s+)?cost)?$");
    private static final Pattern DISABLED_LINE = Pattern.compile("(?i).*\\*\\s*0\\s*\\)?\\s*$");

    record CellSnapshot(
            long cellId,
            String coord,
            int row,
            int col,
            String text,
            BigDecimal numeric,
            String formula,
            boolean error,
            boolean errorDescendant,
            boolean scratch,
            boolean mergedParticipant,
            String cacheState,
            String numberFormat,
            String sheetName) {}

    record RegionSnapshot(
            long regionId,
            String regionKey,
            long mappingId,
            long costHeadId,
            String costHeadCode,
            String schemaJson,
            String headerRowsJson,
            String unit,
            String currency,
            List<CellSnapshot> cells) {}

    record CellParticipation(long cellId, String coord, String participation, String reason) {}

    record Contribution(
            long regionId,
            String regionKey,
            long mappingId,
            Long anchorCellId,
            String basis,
            BigDecimal sourceAmount,
            String sourceUnit,
            String sourceCurrency,
            BigDecimal normalizedAmount,
            String normalizedUnit,
            String normalizedCurrency,
            double confidence,
            List<String> reasons,
            List<CellParticipation> cells) {}

    record Candidate(
            long costHeadId,
            String costHeadCode,
            String fingerprint,
            BigDecimal amount,
            String currency,
            String unit,
            double confidence,
            List<String> reasons,
            boolean review,
            List<Contribution> contributions) {}

    List<Candidate> detect(List<RegionSnapshot> regions, Map<Long, List<Long>> precedents, String fileHash) {
        Map<String, LocatedCell> catalog = catalog(regions);
        Map<String, List<Contribution>> byHead = new LinkedHashMap<>();
        Map<String, Long> headIds = new LinkedHashMap<>();
        List<Contribution> isolated = new ArrayList<>();
        for (RegionSnapshot region : regions) {
            headIds.put(region.costHeadCode(), region.costHeadId());
            RegionOutcome outcome = outcomeFor(region, precedents, catalog);
            if (outcome.composed() != null) {
                byHead.computeIfAbsent(region.costHeadCode(), key -> new ArrayList<>())
                        .add(outcome.composed());
            }
            isolated.addAll(outcome.reviewOnly());
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<Contribution>> entry : byHead.entrySet()) {
            candidates.add(toCandidate(fileHash, headIds.get(entry.getKey()), entry.getKey(),
                    entry.getValue()));
        }
        for (Contribution contribution : isolated) {
            candidates.add(toCandidate(fileHash, headIds.get(costHeadCode(regions, contribution)),
                    costHeadCode(regions, contribution), List.of(contribution)));
        }
        return candidates;
    }

    private RegionOutcome outcomeFor(
            RegionSnapshot region, Map<Long, List<Long>> precedents, Map<String, LocatedCell> catalog) {
        Contribution labelled = contributionFor(region, precedents);
        List<Contribution> structural = structuralContributions(region, precedents, catalog);
        Contribution leaf = leafSumContribution(region, precedents, catalog);
        if (labelled != null) {
            Contribution labelledStructural = structural.stream()
                    .filter(item -> labelled.anchorCellId() != null
                            && labelled.anchorCellId().equals(item.anchorCellId()))
                    .findFirst()
                    .orElse(null);
            if (labelledStructural != null && amountsAgree(labelled, labelledStructural, region)) {
                return new RegionOutcome(withReason(labelled, "STRUCTURAL_AGREEMENT"), List.of());
            }
            List<Contribution> disagreeing = new ArrayList<>();
            for (Contribution item : structural) {
                if (labelledStructural != null && item.anchorCellId().equals(labelledStructural.anchorCellId())) {
                    continue;
                }
                if (!amountsAgree(labelled, item, region)) {
                    disagreeing.add(withReason(item, "STRUCTURAL_AMOUNT_MISMATCH"));
                }
            }
            if (!disagreeing.isEmpty()) {
                List<Contribution> review = new ArrayList<>();
                review.add(withReason(labelled, "STRUCTURAL_AMOUNT_MISMATCH"));
                review.addAll(disagreeing);
                return new RegionOutcome(null, review);
            }
            if (leaf != null && !amountsAgree(labelled, leaf, region)) {
                return new RegionOutcome(null, List.of(
                        withReason(labelled, "STRUCTURAL_AMOUNT_MISMATCH"),
                        withReason(leaf, "STRUCTURAL_AMOUNT_MISMATCH")));
            }
            return new RegionOutcome(labelled, List.of());
        }
        if (structural.size() == 1) {
            return new RegionOutcome(structural.getFirst(), List.of());
        }
        if (structural.size() > 1) {
            List<Contribution> review = new ArrayList<>();
            for (Contribution item : structural) {
                review.add(withReason(item, "STRUCTURAL_AMBIGUOUS"));
            }
            return new RegionOutcome(null, review);
        }
        if (leaf != null) {
            return new RegionOutcome(leaf, List.of());
        }
        return new RegionOutcome(null, periodizedContributions(region, precedents));
    }

    private Contribution contributionFor(RegionSnapshot region, Map<Long, List<Long>> precedents) {
        Set<Integer> amountCols = columnsWithRole(region.schemaJson(), RegionSchemaInferencer.AMOUNT);
        Set<Integer> periodCols = columnsWithRole(region.schemaJson(), RegionSchemaInferencer.PERIOD);
        if (amountCols.isEmpty()) {
            return null;
        }
        Set<Integer> headerRows = headerRows(region.headerRowsJson(), region.cells(), amountCols);
        CellSnapshot anchor = findAnchor(region.cells(), amountCols, headerRows);
        if (anchor == null) {
            return null;
        }
        Set<Long> amountIds = new LinkedHashSet<>();
        for (CellSnapshot cell : region.cells()) {
            if (amountCols.contains(cell.col()) && !headerRows.contains(cell.row())) {
                amountIds.add(cell.cellId());
            }
        }
        boolean connected = reachesAmount(anchor.cellId(), amountIds, precedents, null);
        String basis = connected ? EXPLICIT : LEAF_SUM;
        List<String> reasons = new ArrayList<>();
        reasons.add(connected ? "EXPLICIT_TOTAL_ANCHOR" : "LABELED_LITERAL_GEOMETRIC_ONLY");
        if (connected) {
            reasons.add("FORMULA_CONNECTED");
        }
        String expectedUnit = expectedScale(region.unit(), region.cells(), amountCols, headerRows, true);
        String expectedCurrency = expectedScale(region.currency(), region.cells(), amountCols, headerRows, false);
        List<CellParticipation> participation = new ArrayList<>();
        for (CellSnapshot cell : region.cells()) {
            if (!amountCols.contains(cell.col()) && !periodCols.contains(cell.col())) {
                continue;
            }
            String exclusion = exclusionReason(
                    cell, headerRows, amountCols, periodCols, amountIds, precedents, anchor.cellId(),
                    region.cells(), expectedUnit, expectedCurrency);
            if (exclusion == null) {
                participation.add(new CellParticipation(cell.cellId(), cell.coord(), "included", null));
            } else {
                participation.add(new CellParticipation(cell.cellId(), cell.coord(), "excluded", exclusion));
            }
        }
        BigDecimal amount = connected
                ? (anchor.numeric() == null ? BigDecimal.ZERO : anchor.numeric())
                : includedSum(participation, region.cells());
        BigDecimal normalized = RegionSchemaInferencer.rupees(amount, region.unit(), region.currency());
        boolean converted = normalized != null && amount != null && normalized.compareTo(amount) != 0;
        String normalizedUnit = converted ? RegionSchemaInferencer.UNIT_RS : region.unit();
        String normalizedCurrency = converted ? RegionSchemaInferencer.CURRENCY_INR : region.currency();
        return new Contribution(
                region.regionId(),
                region.regionKey(),
                region.mappingId(),
                anchor.cellId(),
                basis,
                amount,
                region.unit(),
                region.currency(),
                normalized,
                normalizedUnit,
                normalizedCurrency,
                connected ? 0.9 : 0.4,
                reasons,
                participation);
    }

    private List<Contribution> structuralContributions(
            RegionSnapshot region, Map<Long, List<Long>> precedents, Map<String, LocatedCell> catalog) {
        Set<Integer> amountCols = columnsWithRole(region.schemaJson(), RegionSchemaInferencer.AMOUNT);
        Set<Integer> periodCols = columnsWithRole(region.schemaJson(), RegionSchemaInferencer.PERIOD);
        if (amountCols.isEmpty()) {
            return List.of();
        }
        Set<Integer> headerRows = headerRows(region.headerRowsJson(), region.cells(), amountCols);
        Set<Long> amountIds = amountIds(region.cells(), amountCols, headerRows);
        String expectedUnit = expectedScale(region.unit(), region.cells(), amountCols, headerRows, true);
        String expectedCurrency = expectedScale(region.currency(), region.cells(), amountCols, headerRows, false);
        if (!isKnown(region.unit(), RegionSchemaInferencer.UNIT_UNKNOWN)
                || !isKnown(region.currency(), RegionSchemaInferencer.CURRENCY_UNKNOWN)) {
            return List.of();
        }
        List<CellParticipation> leafParticipation = participation(
                region, amountCols, periodCols, headerRows, amountIds, precedents, null,
                expectedUnit, expectedCurrency);
        Set<Long> eligible = includedIds(leafParticipation);
        if (eligible.isEmpty()) {
            return List.of();
        }
        List<Contribution> found = new ArrayList<>();
        for (CellSnapshot cell : region.cells()) {
            if (!amountCols.contains(cell.col()) || headerRows.contains(cell.row())) {
                continue;
            }
            if (cell.formula() == null || cell.formula().isBlank()) {
                continue;
            }
            if (!reachesAmount(cell.cellId(), amountIds, precedents, null)) {
                continue;
            }
            if (qualifiesAsStructural(cell, region, eligible, catalog)) {
                found.add(structuralContribution(
                        region, amountCols, periodCols, headerRows, amountIds, precedents,
                        expectedUnit, expectedCurrency, cell));
            }
        }
        return found;
    }

    private Contribution structuralContribution(
            RegionSnapshot region,
            Set<Integer> amountCols,
            Set<Integer> periodCols,
            Set<Integer> headerRows,
            Set<Long> amountIds,
            Map<Long, List<Long>> precedents,
            String expectedUnit,
            String expectedCurrency,
            CellSnapshot sumCell) {
        List<CellParticipation> participation = participation(
                region, amountCols, periodCols, headerRows, amountIds, precedents, sumCell.cellId(),
                expectedUnit, expectedCurrency);
        BigDecimal amount = sumCell.numeric() == null ? BigDecimal.ZERO : sumCell.numeric();
        BigDecimal normalized = RegionSchemaInferencer.rupees(amount, region.unit(), region.currency());
        boolean converted = normalized != null && normalized.compareTo(amount) != 0;
        String normalizedUnit = converted ? RegionSchemaInferencer.UNIT_RS : region.unit();
        String normalizedCurrency = converted ? RegionSchemaInferencer.CURRENCY_INR : region.currency();
        return new Contribution(
                region.regionId(),
                region.regionKey(),
                region.mappingId(),
                sumCell.cellId(),
                STRUCTURAL,
                amount,
                region.unit(),
                region.currency(),
                normalized,
                normalizedUnit,
                normalizedCurrency,
                0.8,
                structuralReasons(region, periodCols, amountCols, headerRows),
                participation);
    }

    private Contribution leafSumContribution(
            RegionSnapshot region, Map<Long, List<Long>> precedents, Map<String, LocatedCell> catalog) {
        Set<Integer> amountCols = columnsWithRole(region.schemaJson(), RegionSchemaInferencer.AMOUNT);
        Set<Integer> periodCols = columnsWithRole(region.schemaJson(), RegionSchemaInferencer.PERIOD);
        if (amountCols.isEmpty()) {
            return null;
        }
        Set<Integer> headerRows = headerRows(region.headerRowsJson(), region.cells(), amountCols);
        Set<Long> amountIds = amountIds(region.cells(), amountCols, headerRows);
        String expectedUnit = expectedScale(region.unit(), region.cells(), amountCols, headerRows, true);
        String expectedCurrency = expectedScale(region.currency(), region.cells(), amountCols, headerRows, false);
        List<CellParticipation> participation = participation(
                region, amountCols, periodCols, headerRows, amountIds, precedents, null,
                expectedUnit, expectedCurrency);
        Set<Long> eligible = includedIds(participation);
        if (eligible.isEmpty()) {
            return null;
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("LEAF_SUM_FALLBACK");
        reasons.addAll(structuralFailureReasons(region, amountCols, headerRows, eligible, catalog, amountIds,
                precedents));
        if (periodsPartition(region.cells(), amountCols, periodCols, headerRows)) {
            reasons.add("PERIOD_PARTITION");
        }
        BigDecimal amount = includedSum(participation, region.cells());
        BigDecimal normalized = RegionSchemaInferencer.rupees(amount, region.unit(), region.currency());
        boolean converted = normalized != null && normalized.compareTo(amount) != 0;
        return new Contribution(
                region.regionId(),
                region.regionKey(),
                region.mappingId(),
                null,
                LEAF_SUM,
                amount,
                region.unit(),
                region.currency(),
                normalized,
                converted ? RegionSchemaInferencer.UNIT_RS : region.unit(),
                converted ? RegionSchemaInferencer.CURRENCY_INR : region.currency(),
                0.4,
                List.copyOf(reasons),
                participation);
    }

    private List<Contribution> periodizedContributions(RegionSnapshot region, Map<Long, List<Long>> precedents) {
        Set<Integer> amountCols = columnsWithRole(region.schemaJson(), RegionSchemaInferencer.AMOUNT);
        Set<Integer> periodCols = columnsWithRole(region.schemaJson(), RegionSchemaInferencer.PERIOD);
        if (!amountCols.isEmpty() || periodCols.isEmpty()) {
            return List.of();
        }
        Set<Integer> headerRows = headerRows(region.headerRowsJson(), region.cells(), periodCols);
        List<Contribution> contributions = new ArrayList<>();
        for (int col : periodCols) {
            List<CellParticipation> participation = new ArrayList<>();
            for (CellSnapshot cell : region.cells()) {
                if (cell.col() != col) {
                    continue;
                }
                String exclusion = periodExclusion(cell, headerRows);
                if (exclusion == null) {
                    participation.add(new CellParticipation(cell.cellId(), cell.coord(), "included", null));
                } else {
                    participation.add(new CellParticipation(cell.cellId(), cell.coord(), "excluded", exclusion));
                }
            }
            if (includedIds(participation).isEmpty()) {
                continue;
            }
            BigDecimal amount = includedSum(participation, region.cells());
            contributions.add(new Contribution(
                    region.regionId(),
                    region.regionKey(),
                    region.mappingId(),
                    null,
                    LEAF_SUM,
                    amount,
                    region.unit(),
                    region.currency(),
                    amount,
                    region.unit(),
                    region.currency(),
                    0.4,
                    List.of("LEAF_SUM_FALLBACK", "PERIODIZED", "PERIOD_NON_ADDITIVE"),
                    participation));
        }
        return contributions;
    }

    private static String periodExclusion(CellSnapshot cell, Set<Integer> headerRows) {
        if (cell.mergedParticipant()) {
            return MERGED_PARTICIPANT;
        }
        if (headerRows.contains(cell.row())) {
            return HEADER;
        }
        if (cell.error() || cell.errorDescendant()) {
            return ERROR;
        }
        if (cell.scratch()) {
            return SCRATCH;
        }
        if (cell.numeric() == null && (cell.formula() == null || cell.formula().isBlank())) {
            return NOT_AMOUNT;
        }
        return null;
    }

    private static List<String> structuralFailureReasons(
            RegionSnapshot region,
            Set<Integer> amountCols,
            Set<Integer> headerRows,
            Set<Long> eligible,
            Map<String, LocatedCell> catalog,
            Set<Long> amountIds,
            Map<Long, List<Long>> precedents) {
        List<String> reasons = new ArrayList<>();
        if (!isKnown(region.unit(), RegionSchemaInferencer.UNIT_UNKNOWN)) {
            reasons.add("STRUCTURAL_UNKNOWN_UNIT");
        }
        if (!isKnown(region.currency(), RegionSchemaInferencer.CURRENCY_UNKNOWN)) {
            reasons.add("STRUCTURAL_UNKNOWN_CURRENCY");
        }
        CellSnapshot aggregator = null;
        for (CellSnapshot cell : region.cells()) {
            if (!amountCols.contains(cell.col()) || headerRows.contains(cell.row())) {
                continue;
            }
            if (cell.formula() == null || cell.formula().isBlank()) {
                continue;
            }
            if (reachesAmount(cell.cellId(), amountIds, precedents, null)) {
                aggregator = cell;
                break;
            }
        }
        if (aggregator == null) {
            return reasons;
        }
        SumInspection inspection = inspectSum(aggregator, region, catalog);
        reasons.addAll(inspection.blockers());
        if (inspection.deps().isEmpty() && !inspection.blockers().isEmpty()) {
            return reasons;
        }
        Set<Long> unique = inspection.unique();
        if (inspection.duplicates()) {
            reasons.add("STRUCTURAL_DUPLICATE_LEAF");
        }
        if (!unique.equals(eligible)) {
            if (!eligible.containsAll(unique)) {
                for (CellParticipation cell : participationNotes(region, unique, eligible)) {
                    if (cell.reason() != null) {
                        reasons.add("STRUCTURAL_" + cell.reason());
                    }
                }
            }
            if (!unique.containsAll(eligible)) {
                reasons.add("STRUCTURAL_SKIPPED_LEAF");
            }
        }
        BigDecimal leafSum = includedSum(eligibleParticipation(eligible, region.cells()), region.cells());
        if (!NumberFormatPrecision.agree(aggregator.numeric(), leafSum, aggregator.numberFormat())) {
            reasons.add("STRUCTURAL_AMOUNT_MISMATCH");
        }
        return reasons;
    }

    private static List<CellParticipation> participationNotes(
            RegionSnapshot region, Set<Long> deps, Set<Long> eligible) {
        List<CellParticipation> notes = new ArrayList<>();
        for (CellSnapshot cell : region.cells()) {
            if (deps.contains(cell.cellId()) && !eligible.contains(cell.cellId())) {
                notes.add(new CellParticipation(cell.cellId(), cell.coord(), "excluded",
                        cell.error() || cell.errorDescendant() ? ERROR
                                : cell.scratch() ? SCRATCH
                                : PERIOD));
            }
        }
        return notes;
    }

    private static List<String> structuralReasons(
            RegionSnapshot region,
            Set<Integer> periodCols,
            Set<Integer> amountCols,
            Set<Integer> headerRows) {
        List<String> reasons = new ArrayList<>();
        reasons.add("STRUCTURAL_TOTAL");
        if (periodsPartition(region.cells(), amountCols, periodCols, headerRows)) {
            reasons.add("PERIOD_PARTITION");
        }
        return reasons;
    }

    private static boolean qualifiesAsStructural(
            CellSnapshot cell,
            RegionSnapshot region,
            Set<Long> eligible,
            Map<String, LocatedCell> catalog) {
        SumInspection inspection = inspectSum(cell, region, catalog);
        if (!inspection.blockers().isEmpty() || inspection.duplicates()
                || !inspection.unique().equals(eligible)) {
            return false;
        }
        BigDecimal leafSum = includedSum(eligibleParticipation(eligible, region.cells()), region.cells());
        return NumberFormatPrecision.agree(cell.numeric(), leafSum, cell.numberFormat());
    }

    private static SumInspection inspectSum(
            CellSnapshot cell, RegionSnapshot region, Map<String, LocatedCell> catalog) {
        List<String> blockers = new ArrayList<>();
        if (!"fresh".equals(cell.cacheState())) {
            blockers.add("stale".equals(cell.cacheState()) ? "STRUCTURAL_STALE_CACHE" : "STRUCTURAL_MISSING_CACHE");
            return new SumInspection(blockers, List.of());
        }
        FormulaTokenizerResult tokens = FormulaTokenizer.tokenize(
                cell.formula(), cell.row(), cell.col(), Map.of());
        if (!"ok".equals(tokens.formulaState())
                || !SumCoverage.allowlisted(cell.formula(), tokens.functionTokens())) {
            blockers.add("STRUCTURAL_SHAPE_NOT_SUM");
            return new SumInspection(blockers, List.of());
        }
        List<Long> deps = new ArrayList<>();
        String sheet = cell.sheetName();
        for (FormulaToken token : tokens.tokens()) {
            if ("external".equals(token.refKind())
                    || (token.targetSheetName() != null && token.targetSheetName().startsWith("["))) {
                blockers.add("STRUCTURAL_EXTERNAL");
                return new SumInspection(blockers, List.of());
            }
            if (token.targetSheetName() != null && sheet != null
                    && !token.targetSheetName().equals(sheet)) {
                blockers.add("STRUCTURAL_CROSS_REGION");
                return new SumInspection(blockers, List.of());
            }
            if ("defined_name".equals(token.refKind())) {
                blockers.add("STRUCTURAL_SHAPE_NOT_SUM");
                return new SumInspection(blockers, List.of());
            }
            for (SumCoverage.CellRef ref : SumCoverage.expand(token.targetRange())) {
                LocatedCell located = catalog.get(catalogKey(
                        token.targetSheetName() != null ? token.targetSheetName() : sheet,
                        ref.row(), ref.col()));
                if (located == null) {
                    continue;
                }
                if (located.regionId != region.regionId()) {
                    blockers.add("STRUCTURAL_CROSS_REGION");
                    return new SumInspection(blockers, List.of());
                }
                if (located.cell.error() || located.cell.errorDescendant()) {
                    blockers.add("STRUCTURAL_ERROR");
                }
                if (located.cell.scratch() || disabledLine(located.cell.formula())) {
                    blockers.add("STRUCTURAL_SCRATCH");
                }
                deps.add(located.cell.cellId());
            }
        }
        return new SumInspection(blockers, deps);
    }

    private static List<CellParticipation> participation(
            RegionSnapshot region,
            Set<Integer> amountCols,
            Set<Integer> periodCols,
            Set<Integer> headerRows,
            Set<Long> amountIds,
            Map<Long, List<Long>> precedents,
            Long anchorId,
            String expectedUnit,
            String expectedCurrency) {
        List<CellParticipation> participation = new ArrayList<>();
        for (CellSnapshot cell : region.cells()) {
            if (!amountCols.contains(cell.col()) && !periodCols.contains(cell.col())) {
                continue;
            }
            String exclusion = exclusionReason(
                    cell, headerRows, amountCols, periodCols, amountIds, precedents, anchorId,
                    region.cells(), expectedUnit, expectedCurrency);
            if (exclusion == null) {
                participation.add(new CellParticipation(cell.cellId(), cell.coord(), "included", null));
            } else {
                participation.add(new CellParticipation(cell.cellId(), cell.coord(), "excluded", exclusion));
            }
        }
        return participation;
    }

    private static Set<Long> amountIds(List<CellSnapshot> cells, Set<Integer> amountCols, Set<Integer> headerRows) {
        Set<Long> amountIds = new LinkedHashSet<>();
        for (CellSnapshot cell : cells) {
            if (amountCols.contains(cell.col()) && !headerRows.contains(cell.row())) {
                amountIds.add(cell.cellId());
            }
        }
        return amountIds;
    }

    private static Set<Long> includedIds(List<CellParticipation> participation) {
        Set<Long> ids = new LinkedHashSet<>();
        for (CellParticipation cell : participation) {
            if ("included".equals(cell.participation())) {
                ids.add(cell.cellId());
            }
        }
        return ids;
    }

    private static List<CellParticipation> eligibleParticipation(Set<Long> eligible, List<CellSnapshot> cells) {
        List<CellParticipation> participation = new ArrayList<>();
        for (CellSnapshot cell : cells) {
            if (eligible.contains(cell.cellId())) {
                participation.add(new CellParticipation(cell.cellId(), cell.coord(), "included", null));
            }
        }
        return participation;
    }

    private static boolean isKnown(String value, String unknown) {
        return value != null && !value.isBlank() && !unknown.equals(value);
    }

    private static double confidence(List<Contribution> contributions) {
        return contributions.stream().anyMatch(item -> EXPLICIT.equals(item.basis())) ? 0.9 : 0.8;
    }

    private static Map<String, LocatedCell> catalog(List<RegionSnapshot> regions) {
        Map<String, LocatedCell> catalog = new LinkedHashMap<>();
        for (RegionSnapshot region : regions) {
            for (CellSnapshot cell : region.cells()) {
                catalog.put(catalogKey(cell.sheetName(), cell.row(), cell.col()),
                        new LocatedCell(region.regionId(), cell));
            }
        }
        return catalog;
    }

    private static String catalogKey(String sheet, int row, int col) {
        return (sheet == null ? "" : sheet) + '\0' + row + '\0' + col;
    }

    private record LocatedCell(long regionId, CellSnapshot cell) {}

    private record RegionOutcome(Contribution composed, List<Contribution> reviewOnly) {}

    private record SumInspection(List<String> blockers, List<Long> deps) {
        Set<Long> unique() {
            return new LinkedHashSet<>(deps);
        }

        boolean duplicates() {
            return unique().size() != deps.size();
        }
    }

    private Candidate toCandidate(String fileHash, Long costHeadId, String costHeadCode,
            List<Contribution> contributions) {
        List<Contribution> sorted = new ArrayList<>(contributions);
        sorted.sort(Comparator.comparing(Contribution::regionKey));
        boolean review = sorted.stream().anyMatch(item -> LEAF_SUM.equals(item.basis())
                || item.reasons().contains("STRUCTURAL_AMOUNT_MISMATCH")
                || item.reasons().contains("STRUCTURAL_AMBIGUOUS"));
        BigDecimal amount = sorted.stream()
                .map(item -> item.normalizedAmount() != null ? item.normalizedAmount() : item.sourceAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Contribution first = sorted.getFirst();
        return new Candidate(
                costHeadId,
                costHeadCode,
                fingerprint(fileHash, costHeadCode, sorted),
                amount,
                first.normalizedCurrency(),
                first.normalizedUnit(),
                review ? 0.4 : confidence(sorted),
                candidateReasons(sorted),
                review,
                sorted);
    }

    private static String costHeadCode(List<RegionSnapshot> regions, Contribution contribution) {
        for (RegionSnapshot region : regions) {
            if (region.regionId() == contribution.regionId()) {
                return region.costHeadCode();
            }
        }
        return "";
    }

    private static boolean amountsAgree(Contribution left, Contribution right, RegionSnapshot region) {
        String format = null;
        if (left.anchorCellId() != null) {
            for (CellSnapshot cell : region.cells()) {
                if (cell.cellId() == left.anchorCellId()) {
                    format = cell.numberFormat();
                    break;
                }
            }
        }
        return NumberFormatPrecision.agree(left.sourceAmount(), right.sourceAmount(), format);
    }

    private static Contribution withReason(Contribution contribution, String reason) {
        List<String> reasons = new ArrayList<>(contribution.reasons());
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
        return new Contribution(
                contribution.regionId(),
                contribution.regionKey(),
                contribution.mappingId(),
                contribution.anchorCellId(),
                contribution.basis(),
                contribution.sourceAmount(),
                contribution.sourceUnit(),
                contribution.sourceCurrency(),
                contribution.normalizedAmount(),
                contribution.normalizedUnit(),
                contribution.normalizedCurrency(),
                contribution.confidence(),
                List.copyOf(reasons),
                contribution.cells());
    }

    private static boolean periodsPartition(
            List<CellSnapshot> cells,
            Set<Integer> amountCols,
            Set<Integer> periodCols,
            Set<Integer> headerRows) {
        if (periodCols.isEmpty() || amountCols.isEmpty()) {
            return false;
        }
        Map<Integer, BigDecimal> amountByRow = new LinkedHashMap<>();
        Map<Integer, BigDecimal> periodByRow = new LinkedHashMap<>();
        for (CellSnapshot cell : cells) {
            if (headerRows.contains(cell.row()) || cell.numeric() == null) {
                continue;
            }
            if (amountCols.contains(cell.col()) && (cell.formula() == null || cell.formula().isBlank())) {
                amountByRow.merge(cell.row(), cell.numeric(), BigDecimal::add);
            }
            if (periodCols.contains(cell.col()) && !amountCols.contains(cell.col())) {
                periodByRow.merge(cell.row(), cell.numeric(), BigDecimal::add);
            }
        }
        if (amountByRow.isEmpty() || !amountByRow.keySet().equals(periodByRow.keySet())) {
            return false;
        }
        for (Map.Entry<Integer, BigDecimal> entry : amountByRow.entrySet()) {
            if (entry.getValue().compareTo(periodByRow.get(entry.getKey())) != 0) {
                return false;
            }
        }
        return true;
    }

    private static List<String> candidateReasons(List<Contribution> contributions) {
        for (Contribution contribution : contributions) {
            if (EXPLICIT.equals(contribution.basis())) {
                List<String> reasons = new ArrayList<>();
                reasons.add("EXPLICIT_TOTAL_ANCHOR");
                if (contribution.reasons().contains("STRUCTURAL_AGREEMENT")) {
                    reasons.add("STRUCTURAL_AGREEMENT");
                }
                if (contribution.reasons().contains("STRUCTURAL_AMOUNT_MISMATCH")) {
                    reasons.add("STRUCTURAL_AMOUNT_MISMATCH");
                }
                if (contribution.reasons().contains("LABELED_LITERAL_GEOMETRIC_ONLY")) {
                    return List.of("LABELED_LITERAL_GEOMETRIC_ONLY");
                }
                return List.copyOf(reasons);
            }
        }
        if (contributions.stream().allMatch(item -> STRUCTURAL.equals(item.basis()))) {
            return List.of("STRUCTURAL_TOTAL");
        }
        return List.of("LEAF_SUM_FALLBACK");
    }

    private static CellSnapshot findAnchor(
            List<CellSnapshot> cells, Set<Integer> amountCols, Set<Integer> headerRows) {
        Set<Integer> totalRows = new LinkedHashSet<>();
        for (CellSnapshot cell : cells) {
            if (headerRows.contains(cell.row()) || amountCols.contains(cell.col())) {
                continue;
            }
            if (cell.text() != null && TOTAL_LABEL.matcher(cell.text().trim()).matches()) {
                totalRows.add(cell.row());
            }
        }
        CellSnapshot found = null;
        for (CellSnapshot cell : cells) {
            if (!totalRows.contains(cell.row()) || !amountCols.contains(cell.col())) {
                continue;
            }
            if (found == null || (cell.formula() != null && found.formula() == null)) {
                found = cell;
            }
        }
        return found;
    }

    private static String exclusionReason(
            CellSnapshot cell,
            Set<Integer> headerRows,
            Set<Integer> amountCols,
            Set<Integer> periodCols,
            Set<Long> amountIds,
            Map<Long, List<Long>> precedents,
            Long anchorId,
            List<CellSnapshot> regionCells,
            String expectedUnit,
            String expectedCurrency) {
        if (anchorId != null && cell.cellId() == anchorId) {
            return TOTAL_ANCHOR;
        }
        if (cell.mergedParticipant()) {
            return MERGED_PARTICIPANT;
        }
        if (headerRows.contains(cell.row())) {
            return HEADER;
        }
        if (periodCols.contains(cell.col()) && !amountCols.contains(cell.col())) {
            return PERIOD;
        }
        RegionSchemaInferencer.Hint hint = RegionSchemaInferencer.parse(rowText(cell, regionCells));
        if (hint.unit() != null && (expectedUnit == null || !hint.unit().equals(expectedUnit))) {
            return UNIT;
        }
        if (hint.currency() != null && (expectedCurrency == null || !hint.currency().equals(expectedCurrency))) {
            return CURRENCY;
        }
        if (cell.error() || cell.errorDescendant()) {
            return ERROR;
        }
        if (cell.scratch()) {
            return SCRATCH;
        }
        if (cell.formula() != null && !cell.formula().isBlank()
                && reachesAmount(cell.cellId(), amountIds, precedents, anchorId)) {
            return SUBTOTAL;
        }
        if (cell.numeric() == null && (cell.formula() == null || cell.formula().isBlank())) {
            return NOT_AMOUNT;
        }
        return null;
    }

    private static boolean reachesAmount(
            long fromId, Set<Long> amountIds, Map<Long, List<Long>> precedents, Long ignoreId) {
        for (long id : reachable(fromId, precedents)) {
            if (id != fromId && (ignoreId == null || id != ignoreId) && amountIds.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal includedSum(List<CellParticipation> participation, List<CellSnapshot> cells) {
        Map<Long, BigDecimal> byId = new LinkedHashMap<>();
        for (CellSnapshot cell : cells) {
            if (cell.numeric() != null) {
                byId.put(cell.cellId(), cell.numeric());
            }
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (CellParticipation cell : participation) {
            if ("included".equals(cell.participation())) {
                BigDecimal numeric = byId.get(cell.cellId());
                if (numeric != null) {
                    sum = sum.add(numeric);
                }
            }
        }
        return sum;
    }

    private static Set<Long> reachable(long start, Map<Long, List<Long>> precedents) {
        Set<Long> seen = new LinkedHashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            long id = queue.removeFirst();
            for (long next : precedents.getOrDefault(id, List.of())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    private static Set<Integer> columnsWithRole(String schemaJson, String role) {
        Set<Integer> columns = new LinkedHashSet<>();
        if (schemaJson == null || schemaJson.isBlank()) {
            return columns;
        }
        try {
            List<Map<String, Object>> schema = Jsonb.fromJson(schemaJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            for (Map<String, Object> column : schema) {
                if (role.equals(String.valueOf(column.get("role")))) {
                    columns.add(((Number) column.get("col")).intValue());
                }
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid schema_json", e);
        }
        return columns;
    }

    private static Set<Integer> headerRows(
            String headerRowsJson, List<CellSnapshot> cells, Set<Integer> amountCols) {
        Set<Integer> declared = new LinkedHashSet<>();
        if (headerRowsJson != null && !headerRowsJson.isBlank()) {
            try {
                List<?> parsed = Jsonb.fromJson(headerRowsJson, new com.fasterxml.jackson.core.type.TypeReference<List<?>>() {});
                for (Object value : parsed) {
                    if (value instanceof Number number) {
                        declared.add(number.intValue());
                    }
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("invalid header_rows json", e);
            }
        }
        Set<Integer> rows = new LinkedHashSet<>();
        for (int row : declared) {
            boolean data = false;
            for (CellSnapshot cell : cells) {
                if (cell.row() == row && amountCols.contains(cell.col())
                        && (cell.numeric() != null || (cell.formula() != null && !cell.formula().isBlank()))) {
                    data = true;
                    break;
                }
            }
            if (!data) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static String rowText(CellSnapshot target, List<CellSnapshot> cells) {
        StringBuilder text = new StringBuilder();
        for (CellSnapshot cell : cells) {
            if (cell.row() == target.row() && cell.text() != null && !cell.text().isBlank()) {
                text.append(' ').append(cell.text());
            }
        }
        return text.toString();
    }

    private static String expectedScale(
            String regionValue,
            List<CellSnapshot> cells,
            Set<Integer> amountCols,
            Set<Integer> headerRows,
            boolean unit) {
        if (regionValue != null
                && !RegionSchemaInferencer.UNIT_UNKNOWN.equals(regionValue)
                && !RegionSchemaInferencer.CURRENCY_UNKNOWN.equals(regionValue)) {
            return regionValue;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CellSnapshot cell : cells) {
            if (!amountCols.contains(cell.col()) || headerRows.contains(cell.row())) {
                continue;
            }
            RegionSchemaInferencer.Hint hint = RegionSchemaInferencer.parse(rowText(cell, cells));
            String value = unit ? hint.unit() : hint.currency();
            if (value != null) {
                counts.merge(value, 1, Integer::sum);
            }
        }
        String best = null;
        int bestCount = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
                tie = false;
            } else if (entry.getValue() == bestCount) {
                tie = true;
            }
        }
        return tie || bestCount == 0 ? null : best;
    }

    static String fingerprint(String fileHash, String costHeadCode, List<Contribution> contributions) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(fileHash == null ? "" : fileHash).append('\n');
        canonical.append(costHeadCode).append('\n');
        for (Contribution contribution : contributions) {
            canonical.append(contribution.basis()).append('|')
                    .append(contribution.regionKey()).append('|')
                    .append(anchorCoord(contribution)).append('|')
                    .append(contribution.sourceAmount()).append('|')
                    .append(nullToEmpty(contribution.sourceUnit())).append('|')
                    .append(nullToEmpty(contribution.sourceCurrency()));
            List<CellParticipation> cells = new ArrayList<>(contribution.cells());
            cells.sort(Comparator.comparing(CellParticipation::coord));
            for (CellParticipation cell : cells) {
                canonical.append('|').append(cell.coord()).append(':').append(cell.participation());
            }
            canonical.append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String anchorCoord(Contribution contribution) {
        Long anchorId = contribution.anchorCellId();
        if (anchorId == null) {
            return "";
        }
        for (CellParticipation cell : contribution.cells()) {
            if (cell.cellId() == anchorId) {
                return cell.coord();
            }
        }
        return "";
    }

    private static boolean disabledLine(String formula) {
        return formula != null && DISABLED_LINE.matcher(formula.trim()).matches();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
