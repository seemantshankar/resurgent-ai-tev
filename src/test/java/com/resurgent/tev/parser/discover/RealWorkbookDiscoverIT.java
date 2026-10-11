package com.resurgent.tev.parser.discover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.CellCoordRef;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Integration proof for coverage-only discover against client FMs under
 * {@code Project Docs/}. Narrow regions are classify's job.
 */
@Tag("slow")
class RealWorkbookDiscoverIT {

    private static final Path OM_ARHAM =
            Path.of("Project Docs", "OM Arham Ventures.xlsx");

    @TempDir
    static Path tempDir;

    private static Path db;
    private static IngestSummary ingest;
    private static DiscoverSummary discover;

    @BeforeAll
    static void ingestAndDiscoverOmArhamOnce() throws Exception {
        assumeTrue(Files.exists(OM_ARHAM),
                "Working workbook not found at " + OM_ARHAM.toAbsolutePath()
                        + " -- place the client FM at Project Docs/OM Arham Ventures.xlsx"
                        + " to run this integration test; skipping.");
        db = tempDir.resolve("real-workbook-discover.db");
        ingest = new IngestService().ingest(OM_ARHAM, 1L, db);
        discover = new DiscoverService().discover(db, ingest.parseRunId());
    }

    static Stream<Path> clientWorkbooks() {
        return Stream.of(
                        Path.of("Project Docs", "OM Arham Ventures.xlsx"),
                        Path.of("Project Docs", "Recham_FM (Updated).xlsx"),
                        Path.of("Project Docs",
                                "FM_Solar Project - 61.50 Consolidated 170224_Working_NRJ_Draft_Sent to Client.xlsx"),
                        Path.of("Project Docs", "SA hospitalities pvt ltd resort - CLient FM.xlsx"),
                        Path.of("Project Docs", "Jettwings_F.Model_CMA_final 2.xlsx"))
                .filter(Files::exists);
    }

    @ParameterizedTest
    @MethodSource("clientWorkbooks")
    void everyWorkbookHasOneCoverageParentPerSheet(Path workbook) throws Exception {
        Path workbookDb = tempDir.resolve(
                workbook.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_") + ".db");
        IngestSummary summary = new IngestService().ingest(workbook, 1L, workbookDb);
        DiscoverSummary discovered = new DiscoverService().discover(workbookDb, summary.parseRunId());
        assertThat(discovered.coverageCheckPassed()).isTrue();
        assertThat(discovered.candidateCount()).isEqualTo(discovered.worksheetCount());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(workbookDb)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<WorksheetRef> worksheets = repo.selectWorksheetsForParseRun(summary.parseRunId());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(summary.parseRunId());

            assertThat(candidates).hasSize(worksheets.size());
            assertThat(candidates).allMatch(c -> "coverage_parent".equals(c.candidateKind()));
            assertThat(candidates).allMatch(c -> c.structuralRole() == null);

            for (WorksheetRef worksheet : worksheets) {
                Set<Long> cellIds = new HashSet<>();
                for (CellCoordRef cell : repo.selectCellsForWorksheet(worksheet.worksheetId())) {
                    cellIds.add(cell.cellId());
                }
                CandidateRow parent = candidates.stream()
                        .filter(c -> c.worksheetId() == worksheet.worksheetId())
                        .findFirst()
                        .orElseThrow();
                assertThat(new HashSet<>(repo.selectCandidateMemberCellIds(parent.candidateId())))
                        .isEqualTo(cellIds);
            }
        }
    }

    @Test
    void candidatesNeverSpanWorksheets() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            for (CandidateRow candidate : repo.selectCandidatesForParseRun(ingest.parseRunId())) {
                for (Long cellId : repo.selectCandidateMemberCellIds(candidate.candidateId())) {
                    try (var ps = workspace.connection().prepareStatement(
                            "SELECT worksheet_id FROM cell WHERE cell_id = ?")) {
                        ps.setLong(1, cellId);
                        try (var rs = ps.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getLong(1)).isEqualTo(candidate.worksheetId());
                        }
                    }
                }
            }
        }
    }

    @Test
    void reRunDoesNotStackDuplicateCoverageParents() throws Exception {
        DiscoverSummary second = new DiscoverService().discover(db, ingest.parseRunId());
        assertThat(second.candidateCount()).isEqualTo(discover.candidateCount());
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            long coverageCount = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .count();
            assertThat(coverageCount).isEqualTo(discover.worksheetCount());
        }
    }

    @Test
    void discoverSummaryReportsUnavailableIngestSignals() {
        assertThat(discover.unavailableIngestSignals())
                .containsExactly("drawings");
    }
}
