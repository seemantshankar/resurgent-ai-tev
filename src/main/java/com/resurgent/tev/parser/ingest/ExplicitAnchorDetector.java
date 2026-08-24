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
 * Builds explainable cost-head candidates from labelled totals. A formula that
 * reaches the region's line items becomes an explicit total anchor; a labelled
 * literal without that graph evidence stays a review candidate.
 */
final class ExplicitAnchorDetector {

    static final String EXPLICIT = "explicit_total_anchor";
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
            boolean mergedParticipant) {}

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
            long anchorCellId,
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
        Map<String, List<Contribution>> byHead = new LinkedHashMap<>();
        Map<String, Long> headIds = new LinkedHashMap<>();
        for (RegionSnapshot region : regions) {
            Contribution contribution = contributionFor(region, precedents);
            if (contribution == null) {
                continue;
            }
            byHead.computeIfAbsent(region.costHeadCode(), key -> new ArrayList<>()).add(contribution);
            headIds.put(region.costHeadCode(), region.costHeadId());
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<Contribution>> entry : byHead.entrySet()) {
            List<Contribution> contributions = entry.getValue();
            contributions.sort(Comparator.comparing(Contribution::regionKey));
            boolean review = contributions.stream().anyMatch(item -> !EXPLICIT.equals(item.basis()));
            BigDecimal amount = contributions.stream()
                    .map(item -> item.normalizedAmount() != null ? item.normalizedAmount() : item.sourceAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Contribution first = contributions.getFirst();
            String fingerprint = fingerprint(fileHash, entry.getKey(), contributions);
            candidates.add(new Candidate(
                    headIds.get(entry.getKey()),
                    entry.getKey(),
                    fingerprint,
                    amount,
                    first.normalizedCurrency(),
                    first.normalizedUnit(),
                    review ? 0.4 : 0.9,
                    review ? List.of("LABELED_LITERAL_GEOMETRIC_ONLY") : List.of("EXPLICIT_TOTAL_ANCHOR"),
                    review,
                    contributions));
        }
        return candidates;
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
            long anchorId,
            List<CellSnapshot> regionCells,
            String expectedUnit,
            String expectedCurrency) {
        if (cell.cellId() == anchorId) {
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
