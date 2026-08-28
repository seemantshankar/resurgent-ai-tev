package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.resurgent.tev.parser.db.Jsonb;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ticket 12: proves Sprint 1's definition of done against the real reference
 * client FM. The workbook is never committed — {@code fixtures/private/} is
 * gitignored except its README (see {@code fixtures/private/README.md}) — so
 * this test skips with a clear message whenever the file is absent, which is
 * the default for a fresh clone or any CI job that hasn't provisioned the
 * private fixture. It only hard-fails in a private CI job configured to
 * provision the file first.
 *
 * <p>Assertions here stick to structural facts (sheet names, cell coordinates,
 * formula references, dimensions) and never assert on the model's actual
 * financial figures.
 */
class RealWorkbookIT {

    private static final Path WORKBOOK = Path.of("fixtures", "private", "OM Arham Ventures.xlsx");
    private static final Set<String> CANONICAL_ERROR_ENUM = Set.of(
            "#REF!", "#VALUE!", "#DIV/0!", "#NAME?", "#NUM!", "#NULL!", "#N/A");
    private static final Pattern A1_RANGE_END = Pattern.compile(":([A-Z]+)(\\d+)$");

    @TempDir
    static Path tempDir;

    private static Path db;
    private static IngestSummary summary;

    @BeforeAll
    static void ingestRealWorkbookOnce() throws Exception {
        assumeTrue(Files.exists(WORKBOOK),
                "Real workbook fixture not found at " + WORKBOOK.toAbsolutePath()
                        + " -- copy the client FM there to run this integration test locally"
                        + " (see fixtures/private/README.md); skipping.");
        db = tempDir.resolve("real-workbook.db");
        summary = new IngestService().ingest(WORKBOOK, 1L, db);
    }

    private long scalarLong(Connection c, String sql) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void fullIngestSucceedsWithQaGatesGreenOrEveryFailureQueued() throws Exception {
        // The DoD permits either outcome: a clean 'success' status, or every
        // shortfall accounted for in review_queue. This workbook is known-good
        // (qaStatus 'success', 0 cells rejected), so the success branch is what
        // actually runs here — but the queued-failure branch stays a real,
        // reachable path rather than dead code, since neither branch short-circuits
        // into an unconditional 'success' assertion afterwards.
        Map<String, Object> metrics = Jsonb.fromJson(summary.metricsJson(), Map.class);
        if ("success".equals(summary.status())) {
            assertThat(metrics).containsEntry("cellsRejected", 0);
        } else {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(scalarLong(c, "SELECT COUNT(*) FROM review_queue")).isGreaterThan(0);
            }
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM source_file")).isEqualTo(1);
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM parse_run")).isEqualTo(1);
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM cell")).isGreaterThan(0);

            assertThat(scalarLong(c,
                    "SELECT COUNT(*) FROM (SELECT worksheet_id, coord FROM cell"
                            + " GROUP BY worksheet_id, coord HAVING COUNT(*) > 1)"))
                    .isZero();
        }
    }

    @Test
    void everyRetainedCellHasProvenanceAndTheParseRunIsAudited() throws Exception {
        // Ticket 13: provenance/audit_log must be populated by the real ingest
        // path, not merely round-trip in isolation (PersistenceSeamTest already
        // covers that). Story 29/30 require every retained cell traceable back
        // to its (document, sheet, coordinate) and the run itself timestamped.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            long cellCount = scalarLong(c, "SELECT COUNT(*) FROM cell");
            long provenanceCount = scalarLong(c,
                    "SELECT COUNT(*) FROM provenance WHERE entity_type = 'cell'");
            assertThat(provenanceCount).isEqualTo(cellCount);

            // Every provenance row resolves to a real cell via entity_id, joined
            // through its worksheet, with a location matching 'sheet!coord'.
            assertThat(scalarLong(c,
                    "SELECT COUNT(*) FROM provenance p"
                            + " JOIN cell c ON p.entity_id = c.cell_id"
                            + " JOIN worksheet w ON c.worksheet_id = w.worksheet_id"
                            + " WHERE p.entity_type = 'cell'"
                            + " AND p.location = w.sheet_name || '!' || c.coord"
                            + " AND p.source_file_id = " + summary.sourceFileId()
                            + " AND p.parse_run_id = " + summary.parseRunId()))
                    .isEqualTo(cellCount);

            assertThat(scalarLong(c, "SELECT COUNT(*) FROM audit_log"
                    + " WHERE parse_run_id = " + summary.parseRunId()
                    + " AND event_type = 'parse_run_started'"))
                    .isEqualTo(1);
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM audit_log"
                    + " WHERE parse_run_id = " + summary.parseRunId()
                    + " AND event_type = 'parse_run_completed'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void hiddenSheetsAreLoadedIntoTheGraph() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM worksheet WHERE sheet_state = 'hidden'"))
                    .isGreaterThan(0);

            // 'Pages' and 'BS_ANLYSIS' are hidden sheets that visible sheets depend on
            // (CAPITAL COST!C2 = Pages!D26; B  S !D40 = BS_ANLYSIS!J172) — they must be
            // present in the graph, not skipped as "hidden and irrelevant".
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT sheet_state FROM worksheet WHERE sheet_name = 'Pages'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("hidden");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT sheet_state FROM worksheet WHERE sheet_name = 'BS_ANLYSIS'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("hidden");
            }
        }
    }

    @Test
    void externalFormulaTextSurvivesIngest() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT c.formula_text"
                            + " FROM cell c"
                            + " JOIN worksheet w ON c.worksheet_id = w.worksheet_id"
                            + " WHERE w.sheet_name = 'CAPITAL COST' AND c.coord = 'I19'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("formula_text")).contains("[15]Manpower!F35");
            }
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM external_link")).isGreaterThan(0);
        }
    }

    @Test
    void errorCellsUseOnlyTheExactCanonicalErrorEnum() throws Exception {
        // This particular workbook happens to contain #REF!, #VALUE!, and #DIV/0!
        // cascades but no literal #N/A cell -- so rather than assert a literal that
        // isn't in the sample data, this verifies every error_type actually observed
        // is a member of the exact canonical enum (never a mangled or partial string),
        // which is what "exact enum" protects against.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            Set<String> observed = new HashSet<>();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT DISTINCT error_type FROM cell WHERE is_error = 1")) {
                while (rs.next()) {
                    observed.add(rs.getString(1));
                }
            }
            assertThat(observed).isNotEmpty();
            assertThat(CANONICAL_ERROR_ENUM).containsAll(observed);
            assertThat(observed).contains("#REF!", "#VALUE!");
        }
    }

    @Test
    void mergedRangeParticipantsNeverCarryAnAggregableValue() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM cell WHERE is_merged_anchor = 1"))
                    .isGreaterThan(0);
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM cell WHERE is_merged_participant = 1"))
                    .isGreaterThan(0);
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM cell"
                    + " WHERE is_merged_participant = 1 AND numeric_value IS NOT NULL"))
                    .isZero();
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM cell"
                    + " WHERE is_merged_participant = 1 AND value_source != 'merged_anchor'"))
                    .isZero();
        }
    }

    @Test
    void phantomDeclaredDimensionsAreOverriddenByComputedBbox() throws Exception {
        // AT GLANCE declares B3:J44 but real content ends at row 43; SALESPROJECTION
        // declares A2:R83 but the real bounding box never reaches column R; Details
        // declares A1:J235 but real content ends at row 234 -- ws.dimensions()
        // overstates every one of them per the complexity findings.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertBboxNarrowerThanDeclared(c, "AT GLANCE");
            assertBboxNarrowerThanDeclared(c, "SALESPROJECTION");
            assertBboxNarrowerThanDeclared(c, "Details");
        }
    }

    private void assertBboxNarrowerThanDeclared(Connection c, String sheetName) throws Exception {
        int bboxMaxRow;
        int bboxMaxCol;
        String declared;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT bbox_max_row, bbox_max_col, dimensions_declared FROM worksheet"
                        + " WHERE sheet_name = ?")) {
            ps.setString(1, sheetName);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                bboxMaxRow = rs.getInt("bbox_max_row");
                bboxMaxCol = rs.getInt("bbox_max_col");
                declared = rs.getString("dimensions_declared");
            }
        }
        assertThat(declared).isNotNull();
        Matcher m = A1_RANGE_END.matcher(declared);
        assertThat(m.find()).as("dimensions_declared '%s' matches an A1 range", declared).isTrue();
        int declaredMaxRow = Integer.parseInt(m.group(2));
        int declaredMaxCol = columnLettersToIndex(m.group(1));

        assertThat(bboxMaxRow).isLessThanOrEqualTo(declaredMaxRow);
        assertThat(bboxMaxCol).isLessThanOrEqualTo(declaredMaxCol);
        assertThat(bboxMaxRow < declaredMaxRow || bboxMaxCol < declaredMaxCol)
                .as("computed bbox for '%s' (row %d, col %d) is strictly narrower than"
                        + " declared '%s' (row %d, col %d) -- phantom dimensions overridden",
                        sheetName, bboxMaxRow, bboxMaxCol, declared, declaredMaxRow, declaredMaxCol)
                .isTrue();
    }

    private static int columnLettersToIndex(String letters) {
        int index = 0;
        for (char ch : letters.toCharArray()) {
            index = index * 26 + (ch - 'A' + 1);
        }
        return index;
    }

    @Test
    void reingestionIsIdempotentByMandateHashVersionAndConfig() throws Exception {
        long cellCountBefore;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            cellCountBefore = scalarLong(c, "SELECT COUNT(*) FROM cell");
        }

        IngestSummary second = new IngestService().ingest(WORKBOOK, 1L, db);

        assertThat(second.existingRun()).isTrue();
        assertThat(second.parseRunId()).isEqualTo(summary.parseRunId());
        assertThat(second.sourceFileId()).isEqualTo(summary.sourceFileId());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM source_file")).isEqualTo(1);
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM parse_run")).isEqualTo(1);
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM cell")).isEqualTo(cellCountBefore);
        }
    }
}
