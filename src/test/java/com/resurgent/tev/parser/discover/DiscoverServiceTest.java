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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Coverage-only discover: one parent per sheet; narrow regions come from classify. */
class DiscoverServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesOneCoverageParentPerWorksheetContainingEveryCell() throws Exception {
        Path xlsx = tempDir.resolve("cov.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet a = workbook.createSheet("A");
            a.createRow(0).createCell(0).setCellValue("x");
            a.createRow(1).createCell(1).setCellValue(10.0);
            Sheet b = workbook.createSheet("B");
            b.createRow(0).createCell(0).setCellValue("y");
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("cov.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        DiscoverSummary summary = new DiscoverService().discover(db, ingest.parseRunId());

        assertThat(summary.coverageCheckPassed()).isTrue();
        assertThat(summary.worksheetCount()).isEqualTo(2);
        assertThat(summary.candidateCount()).isEqualTo(2);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            assertThat(candidates).hasSize(2);
            assertThat(candidates).allMatch(c -> "coverage_parent".equals(c.candidateKind()));
            assertThat(candidates).allMatch(c -> c.structuralRole() == null);

            for (CandidateRow parent : candidates) {
                Set<Long> cells = new HashSet<>();
                repo.selectCellsForWorksheet(parent.worksheetId())
                        .forEach(cell -> cells.add(cell.cellId()));
                assertThat(new HashSet<>(repo.selectCandidateMemberCellIds(parent.candidateId())))
                        .isEqualTo(cells);
            }
        }
    }

    @Test
    void rediscoverReplacesCandidates() throws Exception {
        Path xlsx = tempDir.resolve("replace.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("S");
            sheet.createRow(0).createCell(0).setCellValue("a");
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("replace.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        DiscoverService service = new DiscoverService();
        service.discover(db, ingest.parseRunId());
        DiscoverSummary second = service.discover(db, ingest.parseRunId());
        assertThat(second.candidateCount()).isEqualTo(1);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            assertThat(new WorkspaceRepository(workspace.connection())
                            .selectCandidatesForParseRun(ingest.parseRunId()))
                    .hasSize(1);
        }
    }

    @Test
    void flagsIsolatedHiddenWorksheet() throws Exception {
        Path xlsx = tempDir.resolve("hidden.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Visible").createRow(0).createCell(0).setCellValue("v");
            Sheet hidden = workbook.createSheet("Scratch");
            hidden.createRow(0).createCell(0).setCellValue("h");
            workbook.setSheetHidden(1, true);
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("hidden.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        DiscoverSummary summary = new DiscoverService().discover(db, ingest.parseRunId());
        assertThat(summary.isolatedHiddenWorksheetCount()).isEqualTo(1);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            CandidateRow scratch = new WorkspaceRepository(workspace.connection())
                    .selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(CandidateRow::isolatedHiddenWorksheet)
                    .findFirst()
                    .orElseThrow();
            assertThat(scratch.candidateKind()).isEqualTo("coverage_parent");
        }
    }

    @Test
    void soleCoverageParentIsSelectedAsDefaultPacket() throws Exception {
        Path xlsx = tempDir.resolve("packet.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Only");
            sheet.createRow(0).createCell(0).setCellValue("label");
            sheet.createRow(0).createCell(1).setCellValue(1.0);
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("packet.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        DiscoverService service = new DiscoverService();
        service.discover(db, ingest.parseRunId());
        List<Packet> packets = service.selectDefaultPackets(db, ingest.parseRunId());
        assertThat(packets).hasSize(1);
        assertThat(packets.get(0).candidateKind()).isEqualTo("coverage_parent");
        assertThat(packets.get(0).cells()).isNotEmpty();
    }

    @Test
    void missingParseRunRejected() throws Exception {
        Path db = tempDir.resolve("missing-pr.db");
        try (var ignored = WorkspaceDatabase.open(db)) {
            // schema only
        }
        assertThatThrownBy(() -> new DiscoverService().discover(db, 999L))
                .isInstanceOf(DiscoverException.class)
                .hasMessageContaining("parse run");
    }

    @Test
    void missingDatabaseRejected() {
        Path missing = tempDir.resolve("absent.db");
        assertThatThrownBy(() -> new DiscoverService().discover(missing, 1L))
                .isInstanceOf(DiscoverException.class)
                .hasMessageContaining("database");
    }
}
