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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration proof for discover against the working client FM under {@code Project Docs/}.
 * Skips when that file is absent. Asserts coverage invariants only — never sheet names,
 * coordinates, or financial amounts as expected values.
 */
class RealWorkbookDiscoverIT {

    private static final Path WORKBOOK =
            Path.of("Project Docs", "OM Arham Ventures.xlsx");

    @TempDir
    static Path tempDir;

    private static Path db;
    private static IngestSummary ingest;
    private static DiscoverSummary discover;

    @BeforeAll
    static void ingestAndDiscoverOnce() throws Exception {
        assumeTrue(Files.exists(WORKBOOK),
                "Working workbook not found at " + WORKBOOK.toAbsolutePath()
                        + " -- place the client FM at Project Docs/OM Arham Ventures.xlsx"
                        + " to run this integration test; skipping.");
        db = tempDir.resolve("real-workbook-discover.db");
        ingest = new IngestService().ingest(WORKBOOK, 1L, db);
        discover = new DiscoverService().discover(db, ingest.parseRunId());
    }

    @Test
    void everyWorksheetHasExactlyOneCoverageParentContainingAllPersistedCells() throws Exception {
        assertThat(discover.coverageCheckPassed()).isTrue();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<WorksheetRef> worksheets = repo.selectWorksheetsForParseRun(ingest.parseRunId());
            assertThat(worksheets).hasSize(discover.worksheetCount());

            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            List<CandidateRow> coverageParents = candidates.stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .toList();
            assertThat(coverageParents).hasSize(discover.worksheetCount());

            for (WorksheetRef worksheet : worksheets) {
                List<CandidateRow> parents = coverageParents.stream()
                        .filter(c -> c.worksheetId() == worksheet.worksheetId())
                        .toList();
                assertThat(parents).hasSize(1);

                Set<Long> cellIds = new HashSet<>();
                for (CellCoordRef cell : repo.selectCellsForWorksheet(worksheet.worksheetId())) {
                    cellIds.add(cell.cellId());
                }
                Set<Long> memberIds = new HashSet<>(
                        repo.selectCandidateMemberCellIds(parents.get(0).candidateId()));
                assertThat(memberIds).isEqualTo(cellIds);
            }
        }
    }

    @Test
    void narrowerCandidatesExistWithoutReducingCoverage() throws Exception {
        assertThat(discover.candidateCount()).isGreaterThan(discover.worksheetCount());
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            assertThat(candidates.stream()
                            .anyMatch(c -> "child".equals(c.candidateKind())
                                    || "parallel".equals(c.candidateKind())
                                    || "overlap".equals(c.candidateKind())))
                    .isTrue();
            assertThat(candidates.stream()
                            .filter(c -> !"coverage_parent".equals(c.candidateKind()))
                            .allMatch(c -> c.structuralConfidence() != null))
                    .isTrue();
        }
    }

    @Test
    void formulaLinkedCandidatesAreRelatedWhenEdgesExist() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            // OM Arham has formula reference edges; linked Candidates should not be
            // discarded solely for distance. Assert relationships exist when edges do.
            if (repo.selectCellReferencesForParseRun(ingest.parseRunId()).isEmpty()) {
                return;
            }
            assertThat(repo.selectCandidateRelatedForParseRun(ingest.parseRunId()))
                    .isNotEmpty();
        }
    }

    @Test
    void packetsDistinguishCoreFromContextWithoutReducingCoverage() throws Exception {
        List<Packet> packets = new DiscoverService()
                .selectDefaultPackets(db, ingest.parseRunId());
        assertThat(packets).isNotEmpty();
        assertThat(packets.stream()
                        .flatMap(p -> p.cells().stream())
                        .anyMatch(c -> PacketCell.ROLE_CORE.equals(c.role())))
                .isTrue();
        boolean hasNarrow = packets.stream()
                .anyMatch(p -> !"coverage_parent".equals(p.candidateKind()));
        if (hasNarrow) {
            assertThat(packets.stream()
                            .filter(p -> !"coverage_parent".equals(p.candidateKind()))
                            .anyMatch(p -> p.cells().stream()
                                    .anyMatch(c -> PacketCell.ROLE_CONTEXT.equals(c.role())
                                            || p.contextClosureSucceeded())))
                    .isTrue();
        }
        assertThat(discover.coverageCheckPassed()).isTrue();
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
    void candidatesWithBboxLargerThanMembersPersistInternalWhitespace() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            boolean found = false;
            for (CandidateRow candidate : repo.selectCandidatesForParseRun(ingest.parseRunId())) {
                if (candidate.bboxMinRow() == null || candidate.bboxMaxRow() == null
                        || candidate.bboxMinCol() == null || candidate.bboxMaxCol() == null) {
                    continue;
                }
                int envelope = (candidate.bboxMaxRow() - candidate.bboxMinRow() + 1)
                        * (candidate.bboxMaxCol() - candidate.bboxMinCol() + 1);
                int members = repo.selectCandidateMemberCellIds(candidate.candidateId()).size();
                if (envelope > members) {
                    assertThat(candidate.internalWhitespaceJson()).isNotNull();
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    void discoverSummaryReportsUnavailableIngestSignals() {
        assertThat(discover.unavailableIngestSignals())
                .contains("column_width", "font", "comments", "drawings");
    }
}
