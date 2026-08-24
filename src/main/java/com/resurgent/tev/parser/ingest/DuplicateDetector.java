package com.resurgent.tev.parser.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.resurgent.tev.parser.db.Jsonb;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares mapped regions that share schema, unit, currency, and cost-head.
 * Never deletes source rows; callers persist structured proposals.
 */
final class DuplicateDetector {

    static final String EXACT = "exact_row_hash";
    static final String SHIFTED = "shifted_block_signature";

    record Proposal(
            long leftRegionId,
            long rightRegionId,
            String leftRegionKey,
            String rightRegionKey,
            String method,
            double score,
            List<String> reasons) {}

    record Decision(
            String leftRegionKey,
            String rightRegionKey,
            String decision,
            String supersededRegionKey) {

        boolean matches(String left, String right) {
            return (leftRegionKey.equals(left) && rightRegionKey.equals(right))
                    || (leftRegionKey.equals(right) && rightRegionKey.equals(left));
        }

        static Decision latest(List<Decision> decisions, String left, String right) {
            Decision found = null;
            for (Decision decision : decisions) {
                if (decision.matches(left, right)) {
                    found = decision;
                }
            }
            return found;
        }
    }

    private DuplicateDetector() {}

    static List<Proposal> detect(List<ExplicitAnchorDetector.RegionSnapshot> regions) {
        List<Proposal> proposals = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); j++) {
                ExplicitAnchorDetector.RegionSnapshot left = regions.get(i);
                ExplicitAnchorDetector.RegionSnapshot right = regions.get(j);
                if (left.regionId() > right.regionId()) {
                    ExplicitAnchorDetector.RegionSnapshot swap = left;
                    left = right;
                    right = swap;
                }
                if (!comparable(left, right)) {
                    continue;
                }
                Signature leftSig = signature(left);
                Signature rightSig = signature(right);
                if (leftSig.dataRows().isEmpty() || rightSig.dataRows().isEmpty()) {
                    continue;
                }
                if (leftSig.contentHash().equals(rightSig.contentHash())) {
                    proposals.add(new Proposal(
                            left.regionId(), right.regionId(), left.regionKey(), right.regionKey(),
                            EXACT, 1.0, List.of("EXACT_CONTENT_HASH")));
                    continue;
                }
                double jaccard = jaccard(tokens(leftSig.descriptions()), tokens(rightSig.descriptions()));
                if (!leftSig.skeletons().equals(rightSig.skeletons()) || jaccard < 0.5) {
                    continue;
                }
                proposals.add(new Proposal(
                        left.regionId(), right.regionId(), left.regionKey(), right.regionKey(),
                        SHIFTED, 0.5 + 0.5 * jaccard, List.of("SHIFTED_BLOCK_SIGNATURE")));
            }
        }
        return proposals;
    }

    static boolean comparable(
            ExplicitAnchorDetector.RegionSnapshot left, ExplicitAnchorDetector.RegionSnapshot right) {
        return Objects.equals(left.costHeadCode(), right.costHeadCode())
                && left.costHeadCode() != null && !left.costHeadCode().isBlank()
                && compatibleScale(left.unit(), right.unit(), RegionSchemaInferencer.UNIT_UNKNOWN)
                && compatibleScale(left.currency(), right.currency(), RegionSchemaInferencer.CURRENCY_UNKNOWN)
                && significantRoles(left.schemaJson()).equals(significantRoles(right.schemaJson()));
    }

    private static boolean compatibleScale(String left, String right, String unknown) {
        return normalizeScale(left, unknown).equals(normalizeScale(right, unknown));
    }

    private static String normalizeScale(String value, String unknown) {
        if (value == null || value.isBlank()) {
            return unknown;
        }
        return value;
    }

    private static List<String> significantRoles(String schemaJson) {
        List<String> roles = new ArrayList<>();
        if (schemaJson == null || schemaJson.isBlank()) {
            return roles;
        }
        try {
            List<Map<String, Object>> columns = Jsonb.fromJson(schemaJson, new TypeReference<>() {});
            for (Map<String, Object> column : columns) {
                String role = String.valueOf(column.get("role"));
                if (!RegionSchemaInferencer.OTHER.equals(role)) {
                    roles.add(role);
                }
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid schema_json", e);
        }
        return roles;
    }

    private static Signature signature(ExplicitAnchorDetector.RegionSnapshot region) {
        Set<Integer> amountCols = amountColumns(region.schemaJson());
        Set<Integer> headers = headerRows(region.headerRowsJson(), region.cells(), amountCols);
        List<String> rowHashes = new ArrayList<>();
        List<String> skeletons = new ArrayList<>();
        Set<String> descriptions = new TreeSet<>();
        for (int row : rowsOf(region.cells())) {
            if (headers.contains(row)) {
                continue;
            }
            String desc = "";
            String amount = "";
            String skeleton = "";
            for (ExplicitAnchorDetector.CellSnapshot cell : region.cells()) {
                if (cell.row() != row) {
                    continue;
                }
                if (amountCols.contains(cell.col())) {
                    if (cell.numeric() != null) {
                        amount = cell.numeric().stripTrailingZeros().toPlainString();
                    }
                    skeleton = skeletonOf(cell);
                } else if (cell.text() != null && !cell.text().isBlank()) {
                    desc = normalize(cell.text());
                }
            }
            if (desc.isEmpty() && amount.isEmpty() && skeleton.isEmpty()) {
                continue;
            }
            if (!desc.isEmpty()) {
                descriptions.add(desc);
            }
            skeletons.add(skeleton);
            rowHashes.add(desc + '|' + amount + '|' + skeleton);
        }
        return new Signature(rowHashes, skeletons, descriptions, sha(String.join("\n", rowHashes)));
    }

    private static Set<Integer> amountColumns(String schemaJson) {
        Set<Integer> columns = new LinkedHashSet<>();
        if (schemaJson == null || schemaJson.isBlank()) {
            return columns;
        }
        try {
            List<Map<String, Object>> schema = Jsonb.fromJson(schemaJson, new TypeReference<>() {});
            for (Map<String, Object> column : schema) {
                if (RegionSchemaInferencer.AMOUNT.equals(String.valueOf(column.get("role")))) {
                    columns.add(((Number) column.get("col")).intValue());
                }
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid schema_json", e);
        }
        return columns;
    }

    private static Set<Integer> headerRows(
            String headerRowsJson,
            List<ExplicitAnchorDetector.CellSnapshot> cells,
            Set<Integer> amountCols) {
        Set<Integer> declared = new LinkedHashSet<>();
        if (headerRowsJson != null && !headerRowsJson.isBlank()) {
            try {
                List<?> parsed = Jsonb.fromJson(headerRowsJson, new TypeReference<List<?>>() {});
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
            for (ExplicitAnchorDetector.CellSnapshot cell : cells) {
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

    private static List<Integer> rowsOf(List<ExplicitAnchorDetector.CellSnapshot> cells) {
        Set<Integer> rows = new TreeSet<>();
        for (ExplicitAnchorDetector.CellSnapshot cell : cells) {
            rows.add(cell.row());
        }
        return List.copyOf(rows);
    }

    private static String skeletonOf(ExplicitAnchorDetector.CellSnapshot cell) {
        if (cell.formula() == null || cell.formula().isBlank()) {
            return "";
        }
        FormulaTokenizerResult tokens = FormulaTokenizer.tokenize(
                cell.formula(), cell.row(), cell.col(), Map.of());
        String skeleton = FormulaSkeletonGenerator.generate(cell.formula(), tokens.tokens());
        return skeleton == null ? "" : skeleton;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static Set<String> tokens(Set<String> descriptions) {
        Set<String> tokens = new TreeSet<>();
        for (String description : descriptions) {
            for (String token : description.split("\\s+")) {
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        int intersection = 0;
        for (String value : left) {
            if (right.contains(value)) {
                intersection++;
            }
        }
        return union.isEmpty() ? 0.0 : (double) intersection / union.size();
    }

    private static String sha(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Signature(
            List<String> dataRows, List<String> skeletons, Set<String> descriptions, String contentHash) {}
}
