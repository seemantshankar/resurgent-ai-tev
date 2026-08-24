package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden semantic output for the private reference workbook.
 *
 * <p>Separate from the region snapshot (ADR 0005). Regeneration is opt-in via
 * {@code -Dsemantic.snapshot.update=true} so an ordinary test run cannot bless a
 * changed baseline. The fixture is skipped when the private workbook is absent.
 * Entries carry roles, codes, bases, trust flags, and structured reasons — never
 * amounts, labels, raw values, or fingerprints.
 */
class SemanticSnapshotIT {

    private static final Path WORKBOOK = Path.of("fixtures", "private", "OM Arham Ventures.xlsx");
    private static final Path SNAPSHOTS = Path.of("fixtures", "semantic-snapshots");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @TempDir
    static Path tempDir;

    private static IngestSummary summary;

    @BeforeAll
    static void ingestPrivateWorkbook() throws Exception {
        assumeTrue(Files.exists(WORKBOOK),
                "Private workbook fixture not found; skipping golden semantic snapshots "
                        + "(see fixtures/private/README.md).");
        summary = new IngestService().ingest(WORKBOOK, 1L, tempDir.resolve("semantic-snapshot.db"));
    }

    @Test
    void semanticsMatchThePerSheetScrubbedGoldenSnapshots() throws Exception {
        Map<String, String> actual = snapshotsFromDatabase(summary.dbPath(), summary.parseRunId());
        assertThat(actual).isNotEmpty();
        assertThat(actual).hasSize(47);
        for (String json : actual.values()) {
            assertThat(json).doesNotContain("fingerprint");
            assertThat(json).doesNotContain("source_label");
            assertThat(json).doesNotContain("\"amount\" :");
            assertThat(json).doesNotContain("\"name\" :");
        }

        if (Boolean.getBoolean("semantic.snapshot.update")) {
            writeSnapshots(actual);
            return;
        }

        for (Map.Entry<String, String> entry : actual.entrySet()) {
            Path snapshot = SNAPSHOTS.resolve(entry.getKey() + ".json");
            assertThat(snapshot)
                    .as("semantic snapshot for %s (regenerate with -Dsemantic.snapshot.update=true)",
                            entry.getKey())
                    .exists();
            assertThat(Files.readString(snapshot, StandardCharsets.UTF_8))
                    .as("semantic snapshot %s", snapshot)
                    .isEqualTo(entry.getValue());
        }
        try (var paths = Files.list(SNAPSHOTS)) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                    .sorted().toList())
                    .containsExactlyElementsOf(actual.keySet().stream().sorted().toList());
        }
    }

    private static Map<String, String> snapshotsFromDatabase(Path dbPath, long parseRunId)
            throws Exception {
        Map<String, Map<String, Object>> sheets = new TreeMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT sheet_index, role, role_conf, role_reasons
                    FROM worksheet WHERE parse_run_id = ? ORDER BY sheet_index
                    """)) {
                statement.setLong(1, parseRunId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String sheetId = sheetId(rows.getInt("sheet_index"));
                        Map<String, Object> sheet = new LinkedHashMap<>();
                        sheet.put("sheet_id", sheetId);
                        sheet.put("role", rows.getString("role"));
                        sheet.put("role_conf", rows.getDouble("role_conf"));
                        sheet.put("role_reasons", scrubRoleReasons(rows.getString("role_reasons")));
                        sheet.put("regions", new ArrayList<Map<String, Object>>());
                        sheet.put("contributions", new ArrayList<Map<String, Object>>());
                        sheet.put("candidates", new ArrayList<Map<String, Object>>());
                        sheet.put("duplicates", new ArrayList<Map<String, Object>>());
                        sheet.put("scratch_cells", new ArrayList<Map<String, Object>>());
                        sheets.put(sheetId, sheet);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT w.sheet_index, r.start_row, r.end_row, r.start_col, r.end_col,
                           r.schema_json, r.inferred_unit, r.inferred_currency,
                           r.inferred_unit_conf, r.inferred_currency_conf,
                           m.match_method, h.code, m.reasons
                    FROM region r
                    JOIN worksheet w ON w.worksheet_id = r.worksheet_id
                    LEFT JOIN cost_head_mapping m ON m.region_id = r.region_id
                    LEFT JOIN cost_head h ON h.cost_head_id = m.cost_head_id
                    WHERE w.parse_run_id = ?
                    ORDER BY w.sheet_index, r.start_row, r.start_col
                    """)) {
                statement.setLong(1, parseRunId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> sheet = sheets.get(sheetId(rows.getInt("sheet_index")));
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> regions =
                                (List<Map<String, Object>>) sheet.get("regions");
                        Map<String, Object> region = new LinkedHashMap<>();
                        region.put("bbox", Map.of(
                                "start_row", rows.getInt("start_row"),
                                "end_row", rows.getInt("end_row"),
                                "start_col", rows.getInt("start_col"),
                                "end_col", rows.getInt("end_col")));
                        region.put("columns", scrubColumns(rows.getString("schema_json")));
                        region.put("inferred_unit", rows.getString("inferred_unit"));
                        region.put("inferred_currency", rows.getString("inferred_currency"));
                        region.put("inferred_unit_conf", rows.getDouble("inferred_unit_conf"));
                        region.put("inferred_currency_conf", rows.getDouble("inferred_currency_conf"));
                        region.put("mapping_method", rows.getString("match_method"));
                        region.put("cost_head_code", rows.getString("code"));
                        region.put("mapping_reasons", scrubStringList(rows.getString("reasons")));
                        regions.add(region);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT w.sheet_index, h.code, contrib.basis, contrib.reasons
                    FROM cost_head_contribution contrib
                    JOIN cost_head_candidate c ON c.cost_head_candidate_id
                        = contrib.cost_head_candidate_id
                    JOIN cost_head h ON h.cost_head_id = c.cost_head_id
                    JOIN region r ON r.region_id = contrib.region_id
                    JOIN worksheet w ON w.worksheet_id = r.worksheet_id
                    WHERE w.parse_run_id = ?
                    ORDER BY w.sheet_index, h.code, contrib.basis
                    """)) {
                statement.setLong(1, parseRunId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> sheet = sheets.get(sheetId(rows.getInt("sheet_index")));
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> contributions =
                                (List<Map<String, Object>>) sheet.get("contributions");
                        Map<String, Object> contribution = new LinkedHashMap<>();
                        contribution.put("cost_head_code", rows.getString("code"));
                        contribution.put("basis", rows.getString("basis"));
                        contribution.put("reasons", scrubStringList(rows.getString("reasons")));
                        contributions.add(contribution);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT w.sheet_index, h.code, c.automatic_trust_eligible, c.reasons
                    FROM cost_head_candidate c
                    JOIN cost_head h ON h.cost_head_id = c.cost_head_id
                    JOIN cost_head_contribution contrib ON contrib.cost_head_candidate_id
                        = c.cost_head_candidate_id
                    JOIN region r ON r.region_id = contrib.region_id
                    JOIN worksheet w ON w.worksheet_id = r.worksheet_id
                    WHERE w.parse_run_id = ?
                    ORDER BY w.sheet_index, h.code
                    """)) {
                statement.setLong(1, parseRunId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> sheet = sheets.get(sheetId(rows.getInt("sheet_index")));
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> candidates =
                                (List<Map<String, Object>>) sheet.get("candidates");
                        Map<String, Object> candidate = new LinkedHashMap<>();
                        candidate.put("cost_head_code", rows.getString("code"));
                        boolean automatic = rows.getInt("automatic_trust_eligible") == 1;
                        candidate.put("automatic_trust_eligible", automatic);
                        candidate.put("trust_state", automatic ? "trusted" : "candidate");
                        candidate.put("reasons", scrubStringList(rows.getString("reasons")));
                        candidates.add(candidate);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT w.sheet_index, p.method, p.reasons
                    FROM duplicate_proposal p
                    JOIN region r ON r.region_id = p.left_region_id
                    JOIN worksheet w ON w.worksheet_id = r.worksheet_id
                    WHERE w.parse_run_id = ?
                    ORDER BY w.sheet_index, p.duplicate_proposal_id
                    """)) {
                statement.setLong(1, parseRunId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> sheet = sheets.get(sheetId(rows.getInt("sheet_index")));
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> duplicates =
                                (List<Map<String, Object>>) sheet.get("duplicates");
                        Map<String, Object> duplicate = new LinkedHashMap<>();
                        duplicate.put("method", rows.getString("method"));
                        duplicate.put("reasons", scrubStringList(rows.getString("reasons")));
                        duplicates.add(duplicate);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT w.sheet_index, c.coord, c.is_scratch, c.is_support, c.is_orphan,
                           c.scratch_reason, c.support_reason
                    FROM cell c
                    JOIN worksheet w ON w.worksheet_id = c.worksheet_id
                    WHERE w.parse_run_id = ?
                    AND (c.is_scratch = 1 OR c.is_support = 1 OR c.is_orphan = 1)
                    ORDER BY w.sheet_index, c.row_num, c.col_num
                    """)) {
                statement.setLong(1, parseRunId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> sheet = sheets.get(sheetId(rows.getInt("sheet_index")));
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> cells =
                                (List<Map<String, Object>>) sheet.get("scratch_cells");
                        Map<String, Object> cell = new LinkedHashMap<>();
                        cell.put("coord", rows.getString("coord"));
                        cell.put("scratch", rows.getInt("is_scratch") == 1);
                        cell.put("support", rows.getInt("is_support") == 1);
                        cell.put("orphan", rows.getInt("is_orphan") == 1);
                        List<String> reasons = new ArrayList<>();
                        if (rows.getString("scratch_reason") != null) {
                            reasons.add(rows.getString("scratch_reason"));
                        }
                        if (rows.getString("support_reason") != null) {
                            reasons.add(rows.getString("support_reason"));
                        }
                        cell.put("reasons", reasons);
                        cells.add(cell);
                    }
                }
            }
        }

        Map<String, String> snapshots = new TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> sheet : sheets.entrySet()) {
            snapshots.put(sheet.getKey(), JSON.writeValueAsString(sheet.getValue()) + "\n");
        }
        return snapshots;
    }

    private static void writeSnapshots(Map<String, String> snapshots) throws IOException {
        Files.createDirectories(SNAPSHOTS);
        if (Files.exists(SNAPSHOTS)) {
            try (var paths = Files.list(SNAPSHOTS)) {
                for (Path path : paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .toList()) {
                    String sheetId = path.getFileName().toString().replaceFirst("\\.json$", "");
                    if (!snapshots.containsKey(sheetId)) {
                        Files.delete(path);
                    }
                }
            }
        }
        for (Map.Entry<String, String> snapshot : snapshots.entrySet()) {
            Files.writeString(SNAPSHOTS.resolve(snapshot.getKey() + ".json"), snapshot.getValue(),
                    StandardCharsets.UTF_8);
        }
    }

    private static String sheetId(int sheetIndex) {
        if (sheetIndex < 0) {
            throw new IllegalArgumentException("worksheet index must be non-negative");
        }
        return String.format(java.util.Locale.ROOT, "sheet-%03d", sheetIndex);
    }

    private static List<Map<String, Object>> scrubColumns(String schemaJson) throws IOException {
        if (schemaJson == null || schemaJson.isBlank()) {
            return List.of();
        }
        JsonNode root = JSON.readTree(schemaJson);
        JsonNode columns = root.isArray() ? root : root.get("columns");
        if (columns == null || !columns.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> scrubbed = new ArrayList<>();
        for (JsonNode column : columns) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (column.has("col")) {
                item.put("col", column.get("col").asInt());
            }
            if (column.has("type")) {
                item.put("type", column.get("type").asText());
            }
            if (column.has("role")) {
                item.put("role", column.get("role").asText());
            }
            if (column.has("conf")) {
                item.put("conf", column.get("conf").asDouble());
            }
            if (column.has("reasons")) {
                item.put("reasons", JSON.convertValue(column.get("reasons"), List.class));
            }
            if (column.has("unit")) {
                item.put("unit", column.get("unit").asText());
            }
            if (column.has("currency")) {
                item.put("currency", column.get("currency").asText());
            }
            scrubbed.add(item);
        }
        return scrubbed;
    }

    private static List<Map<String, Object>> scrubRoleReasons(String reasonsJson) throws IOException {
        if (reasonsJson == null || reasonsJson.isBlank()) {
            return List.of();
        }
        JsonNode reasons = JSON.readTree(reasonsJson);
        if (!reasons.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> scrubbed = new ArrayList<>();
        for (JsonNode reason : reasons) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (reason.has("code")) {
                item.put("code", reason.get("code").asText());
            }
            if (reason.has("weight")) {
                item.put("weight", reason.get("weight").asInt());
            }
            if (reason.has("params") && reason.get("params").isObject()) {
                Map<String, Object> params = new TreeMap<>();
                reason.get("params").fields().forEachRemaining(field -> {
                    if (field.getValue().isNumber()) {
                        params.put(field.getKey(), field.getValue().numberValue());
                    }
                });
                item.put("params", params);
            }
            scrubbed.add(item);
        }
        return scrubbed;
    }

    private static List<String> scrubStringList(String json) throws IOException {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        JsonNode node = JSON.readTree(json);
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values;
    }
}
