package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Header-band scoping: discovery may put a column-header band in its own child
 * Candidate while the data rows form another (SALESPROJECTION!G10 vs G15). The
 * coverage parent spans both, so the resolver must fall back to it when no
 * narrow owner resolves the column header.
 */
class InterpretationEvidenceResolverTest {

    private static final long PARSE_RUN = 1L;
    private static final long WORKSHEET = 100L;

    @Test
    void columnHeaderBandInSeparateChildResolvesViaCoverageParent() {
        // Mirrors SALESPROJECTION: header band row 10 in one child; an intervening
        // label row (13) that stops the nearest-band walk; data rows 15-17 in another.
        InterpretationCellView header = textCell(1L, "G10", 10, 7, "AVERAGE TARIFF (in Rs.)");
        InterpretationCellView upperLabel = textCell(4L, "A13", 13, 1, "Double Bed Room");
        InterpretationCellView label = textCell(2L, "A15", 15, 1, "Deluxe Rooms");
        InterpretationCellView target = numericCell(3L, "G15", 15, 7, "5000");

        CandidateRow parent = candidate(10L, "coverage_parent", null, 1, 1, 83, 15);
        CandidateRow headerChild = candidate(11L, "child", 10L, 9, 1, 10, 9);
        CandidateRow upperBodyChild = candidate(13L, "child", 10L, 12, 1, 13, 4);
        CandidateRow dataChild = candidate(12L, "child", 10L, 15, 1, 17, 9);

        Map<Long, InterpretationCellView> byId =
                Map.of(1L, header, 2L, label, 3L, target, 4L, upperLabel);
        Map<Long, Set<Long>> members = Map.of(
                10L, Set.of(1L, 2L, 3L, 4L),
                11L, Set.of(1L),
                12L, Set.of(2L, 3L),
                13L, Set.of(4L));
        Map<Long, CandidateRow> candidates =
                Map.of(10L, parent, 11L, headerChild, 12L, dataChild, 13L, upperBodyChild);
        Map<Long, List<CandidateRow>> owners = Map.of(
                1L, List.of(parent, headerChild),
                2L, List.of(parent, dataChild),
                3L, List.of(parent, dataChild),
                4L, List.of(parent, upperBodyChild));

        List<InterpretationEvidence> evidence = InterpretationEvidenceResolver.resolve(
                PARSE_RUN, target, byId, owners, members, candidates, null);

        assertThat(evidenceOfRole(evidence, EvidenceRole.COLUMN_HEADER))
                .anyMatch(e -> EvidenceResolution.RESOLVED.equals(e.resolution())
                        && "AVERAGE TARIFF (in Rs.)".equals(e.sourceText())
                        && Long.valueOf(1L).equals(e.sourceCellId()));
        assertThat(evidenceOfRole(evidence, EvidenceRole.ROW_HEADER))
                .anyMatch(e -> EvidenceResolution.RESOLVED.equals(e.resolution())
                        && "Deluxe Rooms".equals(e.sourceText()));
        assertThat(evidenceOfRole(evidence, EvidenceRole.CURRENCY))
                .anyMatch(e -> EvidenceResolution.RESOLVED.equals(e.resolution())
                        && "INR".equals(e.normalizedValue()));
    }

    @Test
    void noHeaderAboveInParentScopeStaysMissing() {
        InterpretationCellView label = textCell(2L, "A15", 15, 1, "Deluxe Rooms");
        InterpretationCellView target = numericCell(3L, "G15", 15, 7, "5000");

        CandidateRow parent = candidate(10L, "coverage_parent", null, 1, 1, 83, 15);
        CandidateRow dataChild = candidate(12L, "child", 10L, 15, 1, 17, 9);

        Map<Long, InterpretationCellView> byId = Map.of(2L, label, 3L, target);
        Map<Long, Set<Long>> members = Map.of(
                10L, Set.of(2L, 3L),
                12L, Set.of(2L, 3L));
        Map<Long, CandidateRow> candidates = Map.of(10L, parent, 12L, dataChild);
        Map<Long, List<CandidateRow>> owners = Map.of(
                2L, List.of(parent, dataChild),
                3L, List.of(parent, dataChild));

        List<InterpretationEvidence> evidence = InterpretationEvidenceResolver.resolve(
                PARSE_RUN, target, byId, owners, members, candidates, null);

        assertThat(evidenceOfRole(evidence, EvidenceRole.COLUMN_HEADER))
                .isNotEmpty()
                .allMatch(e -> EvidenceResolution.MISSING.equals(e.resolution()));
    }

    @Test
    void headerInsideNarrowOwnerResolvesWithoutParentFallback() {
        InterpretationCellView header = textCell(1L, "B1", 1, 2, "Amount");
        InterpretationCellView label = textCell(2L, "A2", 2, 1, "Civil Works");
        InterpretationCellView target = numericCell(3L, "B2", 2, 2, "100");

        CandidateRow parent = candidate(10L, "coverage_parent", null, 1, 1, 2, 2);
        CandidateRow child = candidate(11L, "child", 10L, 1, 1, 2, 2);

        Map<Long, InterpretationCellView> byId = Map.of(1L, header, 2L, label, 3L, target);
        Map<Long, Set<Long>> members = Map.of(
                10L, Set.of(1L, 2L, 3L),
                11L, Set.of(1L, 2L, 3L));
        Map<Long, CandidateRow> candidates = Map.of(10L, parent, 11L, child);
        Map<Long, List<CandidateRow>> owners = Map.of(
                1L, List.of(parent, child),
                2L, List.of(parent, child),
                3L, List.of(parent, child));

        List<InterpretationEvidence> evidence = InterpretationEvidenceResolver.resolve(
                PARSE_RUN, target, byId, owners, members, candidates, null);

        assertThat(evidenceOfRole(evidence, EvidenceRole.COLUMN_HEADER))
                .anyMatch(e -> EvidenceResolution.RESOLVED.equals(e.resolution())
                        && "Amount".equals(e.sourceText()));
    }

    @Test
    void unitCuesFoldCaseAndPluralAcrossRowAndColumnHeaders() {
        InterpretationCellView header =
                textCell(1L, "B1", 1, 2, "TOTAL ROOM SALES (Rs. In Lacs)");
        InterpretationCellView label = textCell(2L, "A2", 2, 1, "Deluxe Rooms");
        InterpretationCellView target = numericCell(3L, "B2", 2, 2, "3467.5");

        CandidateRow parent = candidate(10L, "coverage_parent", null, 1, 1, 2, 2);
        CandidateRow child = candidate(11L, "child", 10L, 1, 1, 2, 2);

        Map<Long, InterpretationCellView> byId = Map.of(1L, header, 2L, label, 3L, target);
        Map<Long, Set<Long>> members = Map.of(
                10L, Set.of(1L, 2L, 3L),
                11L, Set.of(1L, 2L, 3L));
        Map<Long, CandidateRow> candidates = Map.of(10L, parent, 11L, child);
        Map<Long, List<CandidateRow>> owners = Map.of(
                1L, List.of(parent, child),
                2L, List.of(parent, child),
                3L, List.of(parent, child));

        List<InterpretationEvidence> evidence = InterpretationEvidenceResolver.resolve(
                PARSE_RUN, target, byId, owners, members, candidates, null);

        assertThat(evidenceOfRole(evidence, EvidenceRole.UNIT))
                .hasSize(1)
                .first()
                .matches(e -> EvidenceResolution.RESOLVED.equals(e.resolution()))
                .matches(e -> "room".equals(e.normalizedValue()));
        assertThat(evidenceOfRole(evidence, EvidenceRole.CURRENCY))
                .anyMatch(e -> EvidenceResolution.RESOLVED.equals(e.resolution())
                        && "INR".equals(e.normalizedValue()));
        assertThat(evidenceOfRole(evidence, EvidenceRole.SCALE))
                .anyMatch(e -> EvidenceResolution.RESOLVED.equals(e.resolution())
                        && "lakh".equals(e.normalizedValue()));
    }

    @Test
    void assetsF30UsesTheSameResolver() throws Exception {
        Path db = Path.of("Project Docs/om_arham_xlsx_output/workspace-om-arham-visible-layer-a.db");
        Assumptions.assumeTrue(Files.isRegularFile(db));
        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(database.connection());
            long assets = repo.selectWorksheetsForParseRun(1L).stream()
                    .filter(sheet -> "ASSETS".equals(sheet.sheetName()))
                    .findFirst()
                    .orElseThrow()
                    .worksheetId();
            List<InterpretationCellView> cells = repo.selectInterpretationCellsForParseRun(1L);
            Map<Long, InterpretationCellView> byId = InterpretationEvidenceResolver.indexCells(cells);
            InterpretationCellView target = null;
            for (InterpretationCellView cell : cells) {
                if (cell.worksheetId() == assets && "F30".equals(cell.coord())) {
                    target = cell;
                }
            }
            assertThat(target).isNotNull();
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(1L);
            Map<Long, Set<Long>> members = InterpretationEvidenceResolver.indexMembers(
                    repo.selectCandidateMembersForParseRun(1L));
            Map<Long, List<CandidateRow>> owners =
                    InterpretationEvidenceResolver.indexOwners(candidates, members);
            Map<Long, CandidateRow> byCandidate = new java.util.HashMap<>();
            for (CandidateRow candidate : candidates) {
                byCandidate.put(candidate.candidateId(), candidate);
            }
            List<InterpretationEvidence> evidence = InterpretationEvidenceResolver.resolve(
                    1L, target, byId, owners, members, byCandidate, null);
            assertThat(evidenceOfRole(evidence, EvidenceRole.ROW_HEADER))
                    .filteredOn(e -> EvidenceResolution.RESOLVED.equals(e.resolution()))
                    .extracting(InterpretationEvidence::sourceText)
                    .containsExactly("97650 Sqft@ 600 Rs/ Sqft");
            assertThat(evidenceOfRole(evidence, EvidenceRole.COLUMN_HEADER))
                    .filteredOn(e -> EvidenceResolution.RESOLVED.equals(e.resolution()))
                    .extracting(InterpretationEvidence::sourceText)
                    .containsExactly("Amount in Rs");
        }
    }

    @Test
    void verticallyMergedHeaderCountsOnceNotPerMergedCell() {
        // Jettwings Passenger!K19:K20 "Aug": anchor and participant both carry the text.
        InterpretationCellView anchor = merged(1L, "K19", 19, 11, "Aug", true, "K19:K20");
        InterpretationCellView participant = merged(2L, "K20", 20, 11, "Aug", false, "K19:K20");
        InterpretationCellView target = numericCell(3L, "K22", 22, 11, "0");

        assertThat(columnHeaderTexts(target, anchor, participant)).containsExactly("Aug");
    }

    @Test
    void errorCellsAreNotColumnHeaders() {
        // Req_PNB!D20: #REF! cells sit between the real header and the data.
        InterpretationCellView header = textCell(1L, "D5", 5, 4, "Amount");
        InterpretationCellView err1 = errorCell(2L, "D6", 6, 4);
        InterpretationCellView err2 = errorCell(3L, "D7", 7, 4);
        InterpretationCellView target = numericCell(4L, "D8", 8, 4, "0");

        assertThat(columnHeaderTexts(target, header, err1, err2)).containsExactly("Amount");
    }

    private static List<String> columnHeaderTexts(
            InterpretationCellView target, InterpretationCellView... others) {
        java.util.List<InterpretationCellView> all = new java.util.ArrayList<>(List.of(others));
        all.add(target);
        CandidateRow parent = candidate(10L, "coverage_parent", null, 1, 1, 40, 20);
        Map<Long, InterpretationCellView> byId = new java.util.HashMap<>();
        Set<Long> ids = new java.util.HashSet<>();
        Map<Long, List<CandidateRow>> owners = new java.util.HashMap<>();
        for (InterpretationCellView c : all) {
            byId.put(c.cellId(), c);
            ids.add(c.cellId());
            owners.put(c.cellId(), List.of(parent));
        }
        List<InterpretationEvidence> evidence = InterpretationEvidenceResolver.resolve(
                PARSE_RUN, target, byId, owners, Map.of(10L, ids), Map.of(10L, parent), null);
        return evidenceOfRole(evidence, EvidenceRole.COLUMN_HEADER).stream()
                .filter(e -> EvidenceResolution.RESOLVED.equals(e.resolution()))
                .map(InterpretationEvidence::sourceText)
                .toList();
    }

    private static InterpretationCellView merged(
            long id, String coord, int row, int col, String text, boolean anchor, String range) {
        return new InterpretationCellView(
                id, WORKSHEET, coord, row, col, anchor ? "text" : "empty", anchor ? text : null, text,
                null, null, null, null, null, null, null, false, null,
                anchor, !anchor, range, anchor ? "cell" : "merged_anchor");
    }

    private static InterpretationCellView errorCell(long id, String coord, int row, int col) {
        return new InterpretationCellView(
                id, WORKSHEET, coord, row, col, "error", "#REF!", "#REF!",
                null, null, null, null, null, null, null, true, "#REF!",
                false, false, null, "cell");
    }

    private static List<InterpretationEvidence> evidenceOfRole(
            List<InterpretationEvidence> evidence, String role) {
        return evidence.stream().filter(e -> role.equals(e.role())).toList();
    }

    private static InterpretationCellView textCell(
            long id, String coord, int row, int col, String text) {
        return new InterpretationCellView(
                id, WORKSHEET, coord, row, col, "text", text, text,
                null, null, null, null, null, null, null, false, null,
                false, false, null, "cell");
    }

    private static InterpretationCellView numericCell(
            long id, String coord, int row, int col, String numeric) {
        return new InterpretationCellView(
                id, WORKSHEET, coord, row, col, "number", null, numeric,
                numeric, null, null, null, null, null, null, false, null,
                false, false, null, "cell");
    }

    private static CandidateRow candidate(
            long id, String kind, Long parentId, int minRow, int minCol, int maxRow, int maxCol) {
        return new CandidateRow(
                id, PARSE_RUN, WORKSHEET, kind, parentId,
                minRow, minCol, maxRow, maxCol,
                null, null, null, false, null, null, null, "2026-01-01T00:00:00Z", null);
    }
}
