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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden region output for the private reference workbook.
 *
 * <p>The workbook is deliberately absent from a fresh clone and from public CI. Therefore this
 * test skips when {@code fixtures/private/OM Arham Ventures.xlsx} is unavailable. On a machine
 * provisioned with that private fixture, it compares one scrubbed, canonical JSON file per sheet
 * with the persisted region output. Passing {@code -Dsnapshot.update=true} is the sole way to
 * regenerate those files; ordinary test runs can never bless a changed baseline.
 */
class RegionSnapshotIT {

    private static final Path WORKBOOK = Path.of("fixtures", "private", "OM Arham Ventures.xlsx");
    private static final Path SNAPSHOTS = Path.of("fixtures", "snapshots");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @TempDir
    static Path tempDir;

    private static IngestSummary summary;

    @BeforeAll
    static void ingestPrivateWorkbook() throws Exception {
        assumeTrue(Files.exists(WORKBOOK),
                "Private workbook fixture not found; skipping golden region snapshots "
                        + "(see fixtures/private/README.md).");
        summary = new IngestService().ingest(WORKBOOK, 1L, tempDir.resolve("snapshot.db"));
    }

    @Test
    void regionsMatchThePerSheetScrubbedGoldenSnapshots() throws Exception {
        Map<String, String> actual = snapshotsFromDatabase(summary.dbPath(), summary.parseRunId());
        assertThat(actual).isNotEmpty();

        if (Boolean.getBoolean("snapshot.update")) {
            writeSnapshots(actual);
            return;
        }

        for (Map.Entry<String, String> entry : actual.entrySet()) {
            Path snapshot = SNAPSHOTS.resolve(entry.getKey() + ".json");
            assertThat(snapshot)
                    .as("snapshot for %s (regenerate deliberately with -Dsnapshot.update=true)",
                            entry.getKey())
                    .exists();
            assertThat(Files.readString(snapshot, StandardCharsets.UTF_8))
                    .as("snapshot %s", snapshot)
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
        Map<String, List<SnapshotRegion>> regionsById = new TreeMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT w.sheet_index, r.start_row, r.end_row, r.start_col, r.end_col,
                               r.region_type, r.region_conf, r.serial_pattern, r.cost_head_code,
                               r.detection_reasons
                        FROM worksheet w
                        LEFT JOIN region r ON r.worksheet_id = w.worksheet_id
                        WHERE w.parse_run_id = ?
                        ORDER BY w.sheet_index, r.start_row, r.start_col, r.region_key
                        """)) {
            statement.setLong(1, parseRunId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String sheetId = sheetId(rows.getInt("sheet_index"));
                    List<SnapshotRegion> regions = regionsById.computeIfAbsent(sheetId,
                            ignored -> new ArrayList<>());
                    if (rows.getObject("start_row") == null) {
                        continue;
                    }
                    regions.add(new SnapshotRegion(
                            rows.getInt("start_row"), rows.getInt("end_row"),
                            rows.getInt("start_col"), rows.getInt("end_col"),
                            rows.getString("region_type"), rows.getDouble("region_conf"),
                            rows.getString("serial_pattern"), rows.getString("cost_head_code"),
                            scrubReasons(rows.getString("detection_reasons"))));
                }
            }
        }

        Map<String, String> snapshots = new TreeMap<>();
        for (Map.Entry<String, List<SnapshotRegion>> sheet : regionsById.entrySet()) {
            List<SnapshotRegion> regions = sheet.getValue();
            regions.sort(Comparator.comparingInt(SnapshotRegion::startRow)
                    .thenComparingInt(SnapshotRegion::startCol));
            snapshots.put(sheet.getKey(), JSON.writeValueAsString(
                    regions.stream().map(region -> region.toSnapshot(sheet.getKey())).toList())
                    + "\n");
        }
        return snapshots;
    }

    private static void writeSnapshots(Map<String, String> snapshots) throws IOException {
        Files.createDirectories(SNAPSHOTS);
        try (var paths = Files.list(SNAPSHOTS)) {
            for (Path path : paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList()) {
                String sheetId = path.getFileName().toString().replaceFirst("\\.json$", "");
                if (!snapshots.containsKey(sheetId)) {
                    Files.delete(path);
                }
            }
        }
        for (Map.Entry<String, String> snapshot : snapshots.entrySet()) {
            Files.writeString(SNAPSHOTS.resolve(snapshot.getKey() + ".json"), snapshot.getValue(),
                    StandardCharsets.UTF_8);
        }
    }

    /** Opaque, deterministic identifier derived only from the workbook sheet order. */
    private static String sheetId(int sheetIndex) {
        if (sheetIndex < 0) {
            throw new IllegalArgumentException("worksheet index must be non-negative");
        }
        return String.format(java.util.Locale.ROOT, "sheet-%03d", sheetIndex);
    }

    private static List<Map<String, Object>> scrubReasons(String reasonsJson) throws IOException {
        if (reasonsJson == null || reasonsJson.isBlank()) {
            return List.of();
        }
        JsonNode reasons = JSON.readTree(reasonsJson);
        if (!reasons.isArray()) {
            throw new IllegalArgumentException("detection_reasons must be a JSON array");
        }
        List<Map<String, Object>> scrubbed = new ArrayList<>();
        for (JsonNode reason : reasons) {
            JsonNode code = reason.get("code");
            JsonNode weight = reason.get("weight");
            JsonNode params = reason.get("params");
            if (code == null || !code.isTextual() || !code.asText().matches("[A-Z_]+")
                    || weight == null || !weight.isIntegralNumber()
                    || params == null || !params.isObject()) {
                throw new IllegalArgumentException("invalid text-free detection reason");
            }
            Map<String, Long> numericParams = new TreeMap<>();
            params.fields().forEachRemaining(field -> {
                if (!field.getValue().isIntegralNumber()) {
                    throw new IllegalArgumentException("detection-reason params must be integers");
                }
                numericParams.put(field.getKey(), field.getValue().longValue());
            });
            Map<String, Object> scrubbedReason = new LinkedHashMap<>();
            scrubbedReason.put("code", code.asText());
            scrubbedReason.put("weight", weight.longValue());
            scrubbedReason.put("params", numericParams);
            scrubbed.add(scrubbedReason);
        }
        return scrubbed;
    }

    private record SnapshotRegion(int startRow, int endRow, int startCol, int endCol,
            String regionType, double regionConfidence, String serialPattern, String costHeadCode,
            List<Map<String, Object>> detectionReasons) {

        Map<String, Object> toSnapshot(String sheetId) {
            // The database key begins with a private sheet title. Rebuild its structural equivalent
            // from an opaque sheet identifier and anchor, rather than serializing workbook text.
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("bbox", Map.of("end_col", endCol, "end_row", endRow,
                    "start_col", startCol, "start_row", startRow));
            result.put("cost_head_code", costHeadCode);
            result.put("detection_reasons", detectionReasons);
            result.put("region_conf", regionConfidence);
            result.put("region_key", sheetId + "!" + columnName(startCol) + startRow);
            result.put("region_type", regionType);
            result.put("serial_pattern", serialPattern);
            result.put("sheet_id", sheetId);
            return result;
        }
    }

    private static String columnName(int column) {
        StringBuilder result = new StringBuilder();
        for (int value = column; value > 0; value = (value - 1) / 26) {
            result.append((char) ('A' + ((value - 1) % 26)));
        }
        return result.reverse().toString();
    }
}
