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
        assertThat(summary.candidateCount()).isEqualTo(1);
        assertThat(summary.coverageCheckPassed()).isTrue();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            assertThat(candidates).hasSize(1);
            CandidateRow parent = candidates.get(0);
            assertThat(parent.candidateKind()).isEqualTo("coverage_parent");
            assertThat(parent.isolatedHiddenWorksheet()).isFalse();

            Set<Long> memberIds = new HashSet<>(repo.selectCandidateMemberCellIds(parent.candidateId()));
            Set<Long> cellIds = new HashSet<>();
            repo.selectCellsForWorksheet(parent.worksheetId())
                    .forEach(cell -> cellIds.add(cell.cellId()));
            assertThat(memberIds).isEqualTo(cellIds);
            assertThat(memberIds).hasSize(4);
            assertThat(parent.bboxMinRow()).isEqualTo(1);
            assertThat(parent.bboxMaxRow()).isEqualTo(3);
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
        assertThat(summary.candidateCount()).isEqualTo(2);
        assertThat(summary.isolatedHiddenWorksheetCount()).isEqualTo(1);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            assertThat(candidates).hasSize(2);
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

        assertThat(second.candidateCount()).isEqualTo(1);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.countCandidatesForParseRun(ingest.parseRunId())).isEqualTo(1);
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

        assertThat(summary.candidateCount()).isEqualTo(1);
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
}
