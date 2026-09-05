package com.resurgent.tev.parser.discover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behavioural seam for {@code tev-parse discover}: coverage parents from SQLite (#90).
 */
class DiscoverServiceTest {

    @TempDir
    Path tempDir;

    private Path writeWorkbook(XSSFWorkbook workbook, String name) throws Exception {
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        workbook.close();
        return file;
    }

    @Test
    void discoverWritesOneCoverageParentContainingEveryPersistedCell() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            // Gap at row 1 — internal whitespace must not become members
            Row body = sheet.createRow(2);
            body.createCell(0).setCellValue("Civil");
            body.createCell(1).setCellValue(100.0);
            xlsx = writeWorkbook(workbook, "coverage.xlsx");
        }
        Path db = tempDir.resolve("coverage.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);

        DiscoverSummary summary = new DiscoverService().discover(db, ingest.parseRunId());

        assertThat(summary.worksheetCount()).isEqualTo(1);
        assertThat(summary.candidateCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.coverageCheckPassed()).isTrue();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            List<CandidateRow> parents = candidates.stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .toList();
            assertThat(parents).hasSize(1);
            CandidateRow parent = parents.get(0);
            assertThat(parent.isolatedHiddenWorksheet()).isFalse();

            Set<Long> memberIds = new HashSet<>(repo.selectCandidateMemberCellIds(parent.candidateId()));
            Set<Long> cellIds = new HashSet<>();
            repo.selectCellsForWorksheet(parent.worksheetId())
                    .forEach(cell -> cellIds.add(cell.cellId()));
            assertThat(memberIds).isEqualTo(cellIds);
            assertThat(memberIds).hasSize(4);
            assertThat(parent.bboxMinRow()).isEqualTo(1);
            assertThat(parent.bboxMaxRow()).isEqualTo(3);
            // #96: gap row inside bbox is whitespace coords, not members
            assertThat(parent.internalWhitespaceJson()).isNotNull();
            assertThat(parent.internalWhitespaceJson()).contains("\"r\":2");
            assertThat(parent.internalWhitespaceJson()).doesNotContain("cell");
        }
    }

    @Test
    void internalWhitespaceIsCoordinatesOnlyAndMembersUnchanged() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Gaps");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amt");
            // Internal blank row 1 (POI) / DB row 2
            Row body = sheet.createRow(2);
            body.createCell(0).setCellValue("Steel");
            body.createCell(1).setCellValue(50.0);
            // Internal blank column gap: col 2 empty between col 1 and col 3
            body.createCell(3).setCellValue("note");
            xlsx = writeWorkbook(workbook, "whitespace.xlsx");
        }
        Path db = tempDir.resolve("whitespace.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            CandidateRow parent = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .findFirst()
                    .orElseThrow();
            assertThat(repo.selectCandidateMemberCellIds(parent.candidateId())).hasSize(5);
            assertThat(parent.internalWhitespaceJson()).isNotNull();
            assertThat(parent.internalWhitespaceJson()).contains("\"r\":2");
            assertThat(parent.internalWhitespaceJson()).doesNotMatch(".*\"cell_id\".*");
            // Re-run replaces whitespace with the Candidate set
            new DiscoverService().discover(db, ingest.parseRunId());
            CandidateRow again = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .findFirst()
                    .orElseThrow();
            assertThat(again.internalWhitespaceJson()).isEqualTo(parent.internalWhitespaceJson());
        }
    }

    @Test
    void isolatedHiddenWorksheetIsFlaggedNotSkipped() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet visible = workbook.createSheet("Visible");
            visible.createRow(0).createCell(0).setCellValue("ok");
            Sheet hidden = workbook.createSheet("Scratch");
            hidden.createRow(0).createCell(0).setCellValue("draft");
            workbook.setSheetVisibility(1, SheetVisibility.HIDDEN);
            xlsx = writeWorkbook(workbook, "isolated-hidden.xlsx");
        }
        Path db = tempDir.resolve("isolated-hidden.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);

        DiscoverSummary summary = new DiscoverService().discover(db, ingest.parseRunId());

        assertThat(summary.worksheetCount()).isEqualTo(2);
        assertThat(summary.candidateCount()).isGreaterThanOrEqualTo(2);
        assertThat(summary.isolatedHiddenWorksheetCount()).isEqualTo(1);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            assertThat(candidates.stream().filter(c -> "coverage_parent".equals(c.candidateKind())))
                    .hasSize(2);
            CandidateRow scratch = candidates.stream()
                    .filter(CandidateRow::isolatedHiddenWorksheet)
                    .findFirst()
                    .orElseThrow();
            assertThat(scratch.candidateKind()).isEqualTo("coverage_parent");
            assertThat(repo.selectCandidateMemberCellIds(scratch.candidateId())).isNotEmpty();
        }
    }

    @Test
    void hiddenWorksheetLinkedByFormulaIsNotIsolated() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet helper = workbook.createSheet("Helper");
            helper.createRow(0).createCell(0).setCellValue(42.0);
            workbook.setSheetVisibility(0, SheetVisibility.HIDDEN);
            Sheet main = workbook.createSheet("Main");
            main.createRow(0).createCell(0).setCellFormula("Helper!A1");
            xlsx = writeWorkbook(workbook, "linked-hidden.xlsx");
        }
        Path db = tempDir.resolve("linked-hidden.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);

        DiscoverSummary summary = new DiscoverService().discover(db, ingest.parseRunId());

        assertThat(summary.isolatedHiddenWorksheetCount()).isZero();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectCandidatesForParseRun(ingest.parseRunId()))
                    .allMatch(c -> !c.isolatedHiddenWorksheet());
        }
    }

    @Test
    void reRunReplacesCandidatesForSameParseRun() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Only");
            sheet.createRow(0).createCell(0).setCellValue("a");
            xlsx = writeWorkbook(workbook, "rerun.xlsx");
        }
        Path db = tempDir.resolve("rerun.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        DiscoverService service = new DiscoverService();

        service.discover(db, ingest.parseRunId());
        DiscoverSummary second = service.discover(db, ingest.parseRunId());

        assertThat(second.coverageCheckPassed()).isTrue();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            long coverageParents = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .count();
            assertThat(coverageParents).isEqualTo(1);
            assertThat(repo.countCandidatesForParseRun(ingest.parseRunId()))
                    .isEqualTo(second.candidateCount());
        }
    }

    @Test
    void discoverDoesNotRequireSourceWorkbook() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Only");
            sheet.createRow(0).createCell(0).setCellValue("a");
            xlsx = writeWorkbook(workbook, "no-workbook.xlsx");
        }
        Path db = tempDir.resolve("no-workbook.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        java.nio.file.Files.delete(xlsx);

        DiscoverSummary summary = new DiscoverService().discover(db, ingest.parseRunId());

        assertThat(summary.candidateCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.coverageCheckPassed()).isTrue();
    }

    @Test
    void missingParseRunFailsClearly() throws Exception {
        Path db = tempDir.resolve("empty.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(db)) {
            // schema only
        }
        assertThatThrownBy(() -> new DiscoverService().discover(db, 999L))
                .isInstanceOf(DiscoverException.class)
                .hasMessageContaining("parse run");
    }

    @Test
    void missingDatabaseFailsClearly() {
        Path missing = tempDir.resolve("no-such.db");
        assertThatThrownBy(() -> new DiscoverService().discover(missing, 1L))
                .isInstanceOf(DiscoverException.class)
                .hasMessageContaining("database");
    }

    @Test
    void parallelColumnBandsBecomeSiblingCandidatesUnderCoverageParent() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Parallel");
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(10.0 + r);
                row.createCell(4).setCellValue("R" + r);
                row.createCell(5).setCellValue(20.0 + r);
            }
            xlsx = writeWorkbook(workbook, "parallel.xlsx");
        }
        Path db = tempDir.resolve("parallel.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);

        DiscoverSummary summary = new DiscoverService().discover(db, ingest.parseRunId());

        assertThat(summary.coverageCheckPassed()).isTrue();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            CandidateRow coverage = candidates.stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .findFirst()
                    .orElseThrow();
            List<CandidateRow> parallels = candidates.stream()
                    .filter(c -> "parallel".equals(c.candidateKind()))
                    .toList();
            assertThat(parallels).hasSizeGreaterThanOrEqualTo(2);
            assertThat(parallels).allMatch(p -> coverage.candidateId() == p.parentCandidateId());
            assertThat(parallels).allMatch(p -> p.structuralConfidence() != null);

            Set<Long> allCells = new HashSet<>();
            repo.selectCellsForWorksheet(coverage.worksheetId())
                    .forEach(cell -> allCells.add(cell.cellId()));
            assertThat(repo.selectCandidateMemberCellIds(coverage.candidateId()))
                    .containsExactlyInAnyOrderElementsOf(allCells);

            Set<Long> left = new HashSet<>(
                    repo.selectCandidateMemberCellIds(parallels.get(0).candidateId()));
            Set<Long> right = new HashSet<>(
                    repo.selectCandidateMemberCellIds(parallels.get(1).candidateId()));
            assertThat(left).isNotEmpty();
            assertThat(right).isNotEmpty();
            assertThat(left).doesNotContainAnyElementsOf(right);
            for (CandidateRow parallel : parallels) {
                assertThat(parallel.bboxMinCol()).isNotNull();
                assertThat(parallel.bboxMaxCol()).isNotNull();
                assertThat(parallel.bboxMinCol()).isLessThanOrEqualTo(parallel.bboxMaxCol());
            }
        }
    }

    @Test
    void blankBandAloneDoesNotSplitFormCandidate() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Form");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("Project");
            title.createCell(1).setCellValue("Info");
            // Variable blank gaps between label/value rows — soft separators
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("Name");
            r2.createCell(1).setCellValue("Acme");
            Row r5 = sheet.createRow(5);
            r5.createCell(0).setCellValue("City");
            r5.createCell(1).setCellValue("Pune");
            Row r9 = sheet.createRow(9);
            r9.createCell(0).setCellValue("Cost");
            r9.createCell(1).setCellValue(100.0);
            xlsx = writeWorkbook(workbook, "form-blanks.xlsx");
        }
        Path db = tempDir.resolve("form-blanks.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);

        DiscoverSummary summary = new DiscoverService().discover(db, ingest.parseRunId());
        assertThat(summary.coverageCheckPassed()).isTrue();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            CandidateRow coverage = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .findFirst()
                    .orElseThrow();
            // Coverage (and any soft child) spans across blank bands — blanks alone did not drop cells.
            assertThat(coverage.bboxMinRow()).isEqualTo(1);
            assertThat(coverage.bboxMaxRow()).isEqualTo(10);
            assertThat(repo.selectCandidateMemberCellIds(coverage.candidateId())).hasSize(8);

            // Soft merge: not three independent top-level soft clusters as the only structure.
            // Nested hard blocks may exist; at least one Candidate must include cells from
            // both the first and last occupied rows.
            boolean spansBlanks = false;
            for (CandidateRow candidate : repo.selectCandidatesForParseRun(ingest.parseRunId())) {
                Set<Long> members = new HashSet<>(
                        repo.selectCandidateMemberCellIds(candidate.candidateId()));
                Set<Long> firstRow = new HashSet<>();
                Set<Long> lastRow = new HashSet<>();
                for (var cell : repo.selectCellsForWorksheet(coverage.worksheetId())) {
                    if (cell.rowNum() == 1) {
                        firstRow.add(cell.cellId());
                    }
                    if (cell.rowNum() == 10) {
                        lastRow.add(cell.cellId());
                    }
                }
                if (members.containsAll(firstRow) && members.containsAll(lastRow)) {
                    spansBlanks = true;
                    break;
                }
            }
            assertThat(spansBlanks).isTrue();
        }
    }

    @Test
    void nestedSectionsRemainUnderTableWideChild() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Periods");
            // Shared column band across spacer rows; plus an unrelated distant block so the
            // soft table cluster is a proper subset of the coverage parent.
            Row header = sheet.createRow(0);
            header.createCell(1).setCellValue("Y1");
            header.createCell(2).setCellValue("Y2");
            header.createCell(3).setCellValue("Y3");
            for (int r = 1; r <= 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("A" + r);
                row.createCell(1).setCellValue(r);
                row.createCell(2).setCellValue(r * 2.0);
                row.createCell(3).setCellValue(r * 3.0);
            }
            // Section B: same column band but text-only values — signature change across spacer
            for (int r = 5; r <= 7; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("Note" + r);
                row.createCell(1).setCellValue("x");
                row.createCell(2).setCellValue("y");
                row.createCell(3).setCellValue("z");
            }
            Row distant = sheet.createRow(20);
            distant.createCell(10).setCellValue("meta");
            distant.createCell(11).setCellValue("only");
            xlsx = writeWorkbook(workbook, "nested.xlsx");
        }
        Path db = tempDir.resolve("nested.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);

        DiscoverSummary summary = new DiscoverService().discover(db, ingest.parseRunId());
        assertThat(summary.coverageCheckPassed()).isTrue();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> children = repo.selectCandidatesForParseRun(ingest.parseRunId())
                    .stream()
                    .filter(c -> "child".equals(c.candidateKind()))
                    .toList();
            assertThat(children).hasSizeGreaterThanOrEqualTo(3);
            CandidateRow tableWide = children.stream()
                    .filter(c -> c.bboxMinRow() != null && c.bboxMaxRow() != null
                            && c.bboxMaxRow() - c.bboxMinRow() >= 6)
                    .findFirst()
                    .orElseThrow();
            assertThat(repo.selectCandidateMemberCellIds(tableWide.candidateId()).size())
                    .isGreaterThan(8);
            assertThat(children.stream()
                            .filter(c -> c.candidateId() != tableWide.candidateId())
                            .count())
                    .isGreaterThanOrEqualTo(2);
            assertThat(children).allMatch(c -> c.structuralConfidence() != null);
        }
    }

    @Test
    void wideAndNarrowGroupingsBothRemainWithConfidence() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Both");
            for (int r = 0; r < 4; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(1.0);
                row.createCell(5).setCellValue("R" + r);
                row.createCell(6).setCellValue(2.0);
            }
            xlsx = writeWorkbook(workbook, "wide-narrow.xlsx");
        }
        Path db = tempDir.resolve("wide-narrow.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);

        new DiscoverService().discover(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            assertThat(candidates.stream().filter(c -> "parallel".equals(c.candidateKind())))
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat(candidates.stream().filter(c -> "overlap".equals(c.candidateKind())))
                    .isNotEmpty();
            assertThat(candidates.stream()
                            .filter(c -> !"coverage_parent".equals(c.candidateKind()))
                            .allMatch(c -> c.structuralConfidence() != null))
                    .isTrue();
        }
    }

    @Test
    void sameSheetDistantFormulaHelperIsRelatedNotMerged() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("PL");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("Item");
            h.createCell(1).setCellValue("Amt");
            Row body = sheet.createRow(1);
            body.createCell(0).setCellValue("Rev");
            body.createCell(1).setCellValue(100.0);
            // Distant helper block — different column band, formula back to main
            Row helper = sheet.createRow(40);
            helper.createCell(5).setCellFormula("B2");
            helper.createCell(6).setCellValue("check");
            xlsx = writeWorkbook(workbook, "distant-helper.xlsx");
        }
        Path db = tempDir.resolve("distant-helper.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);

        DiscoverSummary summary = new DiscoverService().discover(db, ingest.parseRunId());
        assertThat(summary.coverageCheckPassed()).isTrue();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> narrow = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> !"coverage_parent".equals(c.candidateKind()))
                    .toList();
            assertThat(narrow.size()).isGreaterThanOrEqualTo(2);

            List<long[]> related = repo.selectCandidateRelatedForParseRun(ingest.parseRunId());
            assertThat(related).isNotEmpty();

            // Helper cells must not be members of the main table child (distance preserved).
            CandidateRow mainish = narrow.stream()
                    .filter(c -> c.bboxMaxRow() != null && c.bboxMaxRow() <= 5)
                    .findFirst()
                    .orElseThrow();
            Set<Long> mainMembers = new HashSet<>(
                    repo.selectCandidateMemberCellIds(mainish.candidateId()));
            for (var cell : repo.selectCellsForWorksheet(mainish.worksheetId())) {
                if (cell.rowNum() >= 40) {
                    assertThat(mainMembers).doesNotContain(cell.cellId());
                }
            }
        }
    }

    @Test
    void crossSheetFormulaCreatesRelatedWithoutMergingMembers() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet helper = workbook.createSheet("Helper");
            helper.createRow(0).createCell(0).setCellValue(42.0);
            helper.createRow(1).createCell(0).setCellValue(43.0);
            Sheet main = workbook.createSheet("Main");
            main.createRow(0).createCell(0).setCellFormula("Helper!A1");
            main.createRow(1).createCell(0).setCellValue("label");
            xlsx = writeWorkbook(workbook, "cross-sheet-rel.xlsx");
        }
        Path db = tempDir.resolve("cross-sheet-rel.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);

        new DiscoverService().discover(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectCandidateRelatedForParseRun(ingest.parseRunId())).isNotEmpty();

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
    void resemblanceWithoutReferenceEdgeDoesNotCreateRelationship() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Twin");
            // Two similar stacked schedules, no formulas between them
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("A" + r);
                row.createCell(1).setCellValue(r);
            }
            for (int r = 10; r < 13; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("B" + r);
                row.createCell(1).setCellValue(r);
            }
            xlsx = writeWorkbook(workbook, "resemble.xlsx");
        }
        Path db = tempDir.resolve("resemble.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);

        new DiscoverService().discover(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectCandidateRelatedForParseRun(ingest.parseRunId())).isEmpty();
            // #98: stacked same-shape schedules → sibling narrower children, not one soft merge
            List<CandidateRow> children = repo.selectCandidatesForParseRun(ingest.parseRunId())
                    .stream()
                    .filter(c -> "child".equals(c.candidateKind()))
                    .toList();
            assertThat(children).hasSizeGreaterThanOrEqualTo(2);
            assertThat(children.stream()
                            .anyMatch(c -> c.bboxMaxRow() != null && c.bboxMaxRow() >= 11
                                    && c.bboxMinRow() != null && c.bboxMinRow() <= 3))
                    .isFalse();
        }
    }

    @Test
    void blankContinuationLabelRowsStayWithGroupOpener() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Cont");
            Row opener = sheet.createRow(0);
            opener.createCell(0).setCellValue("GroupA");
            opener.createCell(1).setCellValue(10.0);
            opener.createCell(2).setCellValue(20.0);
            // Continuation rows: blank label column, value columns continue
            for (int r = 1; r <= 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(1).setCellValue(10.0 + r);
                row.createCell(2).setCellValue(20.0 + r);
            }
            Row distant = sheet.createRow(30);
            distant.createCell(8).setCellValue("meta");
            distant.createCell(9).setCellValue("x");
            xlsx = writeWorkbook(workbook, "continuation.xlsx");
        }
        Path db = tempDir.resolve("continuation.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            CandidateRow coverage = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .findFirst()
                    .orElseThrow();
            Set<Long> groupCells = new HashSet<>();
            for (var cell : repo.selectCellsForWorksheet(coverage.worksheetId())) {
                if (cell.rowNum() <= 4) {
                    groupCells.add(cell.cellId());
                }
            }
            boolean together = false;
            for (CandidateRow candidate : repo.selectCandidatesForParseRun(ingest.parseRunId())) {
                if ("coverage_parent".equals(candidate.candidateKind())) {
                    continue;
                }
                Set<Long> members = new HashSet<>(
                        repo.selectCandidateMemberCellIds(candidate.candidateId()));
                if (members.containsAll(groupCells)) {
                    together = true;
                    break;
                }
            }
            assertThat(together).isTrue();
            // Coverage still owns every cell (no Scratch/Orphan assignment)
            assertThat(repo.selectCandidateMemberCellIds(coverage.candidateId()))
                    .hasSize(groupCells.size() + 2);
        }
    }

    @Test
    void heterogeneousSectionsGetLocallyReAnchoredChildren() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Hetero");
            Row h1 = sheet.createRow(0);
            h1.createCell(0).setCellValue("Item");
            h1.createCell(1).setCellValue("Qty");
            for (int r = 1; r <= 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("A" + r);
                row.createCell(1).setCellValue(r);
            }
            // Different column band + value types → local re-anchor (#101)
            Row h2 = sheet.createRow(8);
            h2.createCell(4).setCellValue("Note");
            h2.createCell(5).setCellValue("Flag");
            h2.createCell(6).setCellValue("Score");
            for (int r = 9; r <= 11; r++) {
                Row row = sheet.createRow(r);
                row.createCell(4).setCellValue("n" + r);
                row.createCell(5).setCellValue("y");
                row.createCell(6).setCellValue(r * 1.5);
            }
            xlsx = writeWorkbook(workbook, "hetero.xlsx");
        }
        Path db = tempDir.resolve("hetero.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            assertThat(candidates.stream().filter(c -> "coverage_parent".equals(c.candidateKind())))
                    .hasSize(1);
            List<CandidateRow> children = candidates.stream()
                    .filter(c -> "child".equals(c.candidateKind())
                            || "parallel".equals(c.candidateKind()))
                    .toList();
            assertThat(children).hasSizeGreaterThanOrEqualTo(2);
            // Additive: parallel/overlap patterns still allowed elsewhere
            assertThat(candidates.stream().anyMatch(c -> c.structuralConfidence() != null)).isTrue();

            DiscoverService service = new DiscoverService();
            for (CandidateRow child : children) {
                Packet packet = service.buildPacket(db, child.candidateId());
                assertThat(packet.cells().stream().anyMatch(c -> PacketCell.ROLE_CORE.equals(c.role())))
                        .isTrue();
                // #101 / #93: local header/axis is either already core (re-anchored with the
                // section) or appended as context from rows above / label columns.
                boolean hasLocalHeaderAsCore = packet.cells().stream()
                        .anyMatch(c -> PacketCell.ROLE_CORE.equals(c.role())
                                && ("text".equals(c.valueType()) || "quantity_text".equals(c.valueType())));
                boolean hasContext = packet.contextClosureSucceeded()
                        || packet.cells().stream()
                                .anyMatch(c -> PacketCell.ROLE_CONTEXT.equals(c.role()));
                assertThat(hasLocalHeaderAsCore || hasContext).isTrue();
            }
        }
    }

    @Test
    void narrowerCandidatesPersistAnchorsAndStructuralSignatures() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sig");
            var font = workbook.createFont();
            font.setBold(true);
            var bold = workbook.createCellStyle();
            bold.setFont(font);
            Row header = sheet.createRow(0);
            var h0 = header.createCell(0);
            h0.setCellValue("Title");
            h0.setCellStyle(bold);
            header.createCell(1).setCellValue("Y1");
            header.createCell(2).setCellValue("Y2");
            for (int r = 1; r <= 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("R" + r);
                row.createCell(1).setCellValue(r);
                row.createCell(2).setCellValue(r * 2.0);
            }
            Row distant = sheet.createRow(20);
            distant.createCell(8).setCellValue("meta");
            distant.createCell(9).setCellValue("x");
            xlsx = writeWorkbook(workbook, "signatures.xlsx");
        }
        Path db = tempDir.resolve("signatures.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> narrow = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> !"coverage_parent".equals(c.candidateKind()))
                    .toList();
            assertThat(narrow).isNotEmpty();
            assertThat(narrow.stream().anyMatch(c -> c.structuralSignaturesJson() != null)).isTrue();
            assertThat(narrow.stream()
                            .filter(c -> c.structuralSignaturesJson() != null)
                            .allMatch(c -> c.structuralSignaturesJson().contains("occupied_cols")))
                    .isTrue();
            assertThat(narrow.stream().anyMatch(c -> c.anchorsJson() != null
                    && (c.anchorsJson().contains("bold") || c.anchorsJson().contains("text_row"))))
                    .isTrue();
            String sigBefore = narrow.stream()
                    .map(CandidateRow::structuralSignaturesJson)
                    .filter(s -> s != null)
                    .findFirst()
                    .orElseThrow();
            String anchorsBefore = narrow.stream()
                    .map(CandidateRow::anchorsJson)
                    .filter(a -> a != null)
                    .findFirst()
                    .orElse(null);
            new DiscoverService().discover(db, ingest.parseRunId());
            List<CandidateRow> again = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> !"coverage_parent".equals(c.candidateKind()))
                    .toList();
            assertThat(again.stream().anyMatch(c -> sigBefore.equals(c.structuralSignaturesJson())))
                    .isTrue();
            if (anchorsBefore != null) {
                assertThat(again.stream().anyMatch(c -> anchorsBefore.equals(c.anchorsJson())))
                        .isTrue();
            }
        }
    }

    @Test
    void packetMarksCoreVersusContextAndDoesNotEnlargeCandidateMembers() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Ctx");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Year");
            header.createCell(1).setCellValue("Y1");
            header.createCell(2).setCellValue("Y2");
            for (int r = 2; r <= 4; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("R" + r);
                row.createCell(1).setCellValue(r);
                row.createCell(2).setCellValue(r * 10.0);
            }
            // Distant block so the data section is a proper subset child
            Row meta = sheet.createRow(20);
            meta.createCell(8).setCellValue("meta");
            meta.createCell(9).setCellValue("x");
            xlsx = writeWorkbook(workbook, "packet-ctx.xlsx");
        }
        Path db = tempDir.resolve("packet-ctx.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        DiscoverService service = new DiscoverService();
        service.discover(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            CandidateRow child = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> "child".equals(c.candidateKind()))
                    .filter(c -> c.bboxMinRow() != null && c.bboxMinRow() >= 3)
                    .findFirst()
                    .orElseThrow();
            int memberCountBefore = repo.selectCandidateMemberCellIds(child.candidateId()).size();

            Packet packet = service.buildPacket(db, child.candidateId());

            assertThat(packet.cells().stream().filter(c -> PacketCell.ROLE_CORE.equals(c.role())))
                    .isNotEmpty();
            assertThat(packet.cells().stream().anyMatch(c -> PacketCell.ROLE_CONTEXT.equals(c.role())))
                    .isTrue();
            assertThat(repo.selectCandidateMemberCellIds(child.candidateId()))
                    .hasSize(memberCountBefore);
            assertThat(child.bboxMinRow()).isEqualTo(packet.cells().stream()
                    .filter(c -> PacketCell.ROLE_CORE.equals(c.role()))
                    .mapToInt(PacketCell::rowNum)
                    .min()
                    .orElseThrow());
        }
    }

    @Test
    void largeFormulaRangeIsMetadataNotInlined() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet data = workbook.createSheet("Data");
            for (int r = 0; r < 70; r++) {
                data.createRow(r).createCell(0).setCellValue(r);
            }
            Sheet main = workbook.createSheet("Main");
            Row row = main.createRow(0);
            row.createCell(0).setCellValue("total");
            row.createCell(1).setCellFormula("SUM(Data!A1:A70)");
            xlsx = writeWorkbook(workbook, "large-range.xlsx");
        }
        Path db = tempDir.resolve("large-range.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        DiscoverService service = new DiscoverService();
        service.discover(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            CandidateRow mainCoverage = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .filter(c -> {
                        try {
                            return repo.selectCandidateMemberCellIds(c.candidateId()).size() <= 5;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .findFirst()
                    .orElseThrow();

            Packet packet = service.buildPacket(db, mainCoverage.candidateId());
            assertThat(packet.largeRangeRefs()).isNotEmpty();
            assertThat(packet.largeRangeRefs().get(0).persistedCellCount())
                    .isGreaterThan(PacketBuilder.INLINE_FORMULA_CELL_CAP);
            long dataSheetContext = packet.cells().stream()
                    .filter(c -> PacketCell.ROLE_CONTEXT.equals(c.role()))
                    .filter(c -> c.worksheetId() != mainCoverage.worksheetId())
                    .count();
            assertThat(dataSheetContext).isLessThanOrEqualTo(PacketBuilder.INLINE_FORMULA_CELL_CAP);
        }
    }

    @Test
    void defaultPacketSelectionOmitsCoverageWhenChildrenStandAlone() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Select");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("H");
            header.createCell(1).setCellValue("Y1");
            for (int r = 2; r <= 4; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("R" + r);
                row.createCell(1).setCellValue(r);
            }
            Row distant = sheet.createRow(15);
            distant.createCell(5).setCellValue("other");
            distant.createCell(6).setCellValue(1.0);
            xlsx = writeWorkbook(workbook, "packet-select.xlsx");
        }
        Path db = tempDir.resolve("packet-select.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        DiscoverService service = new DiscoverService();
        service.discover(db, ingest.parseRunId());

        List<Packet> packets = service.selectDefaultPackets(db, ingest.parseRunId());
        assertThat(packets).isNotEmpty();
        assertThat(packets.stream().anyMatch(p -> !"coverage_parent".equals(p.candidateKind())))
                .isTrue();
        boolean allChildrenClosed = packets.stream()
                .filter(p -> !"coverage_parent".equals(p.candidateKind()))
                .allMatch(Packet::contextClosureSucceeded);
        if (allChildrenClosed) {
            assertThat(packets.stream().noneMatch(p -> "coverage_parent".equals(p.candidateKind())))
                    .isTrue();
        }
    }

    @Test
    void soleCoverageParentGetsAPacket() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Only");
            sheet.createRow(0).createCell(0).setCellValue("solo");
            xlsx = writeWorkbook(workbook, "sole.xlsx");
        }
        Path db = tempDir.resolve("sole.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        DiscoverService service = new DiscoverService();
        service.discover(db, ingest.parseRunId());

        List<Packet> packets = service.selectDefaultPackets(db, ingest.parseRunId());
        assertThat(packets).hasSize(1);
        assertThat(packets.get(0).candidateKind()).isEqualTo("coverage_parent");
        assertThat(packets.get(0).cells()).allMatch(c -> PacketCell.ROLE_CORE.equals(c.role()));
    }
}
