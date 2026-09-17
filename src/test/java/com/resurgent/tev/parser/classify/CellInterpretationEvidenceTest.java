package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Primary seam: ingest → discover → classify → cell-meaning for #117 evidence. */
class CellInterpretationEvidenceTest {

    private static final String AC_PATH = "Project Cost > Plant & Machinery > Air Conditioning";

    @TempDir
    Path tempDir;

    @Test
    void evidenceRolesRoundTripViaCellMeaningWithResolutionStates() throws Exception {
        Path xlsx = simpleGridWorkbook();
        Path db = tempDir.resolve("roundtrip.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(bindingLlm("B2")).classify(db, ingest.parseRunId());

        CellMeaning amount = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B2");
        assertThat(amount.interpretation()).isNotNull();
        assertThat(amount.evidence()).isNotEmpty();

        InterpretationEvidence row = evidence(amount, EvidenceRole.ROW_HEADER).get(0);
        assertThat(row.resolution()).isEqualTo(EvidenceResolution.RESOLVED);
        assertThat(row.sourceText()).isEqualTo("Civil Works");
        assertThat(row.sourceCellId()).isNotNull();
        assertThat(row.ordinal()).isZero();

        InterpretationEvidence col = evidence(amount, EvidenceRole.COLUMN_HEADER).get(0);
        assertThat(col.resolution()).isEqualTo(EvidenceResolution.RESOLVED);
        assertThat(col.sourceText()).isEqualTo("Amount");
        assertThat(col.sourceCellId()).isNotNull();

        Path db2 = tempDir.resolve("before.db");
        IngestSummary ingest2 = new IngestService().ingest(xlsx, 1L, db2);
        new DiscoverService().discover(db2, ingest2.parseRunId());
        CellMeaning beforeClassify =
                new CellMeaningService().lookup(db2, ingest2.parseRunId(), "Costs!B2");
        assertThat(beforeClassify.interpretation()).isNull();
        assertThat(beforeClassify.evidence()).isEmpty();
    }

    @Test
    void multiLevelAndNumericYearHeadersResolveWithSourceIds() throws Exception {
        Path xlsx = multiLevelWorkbook();
        Path db = tempDir.resolve("multilevel.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(bindingLlm("B3")).classify(db, ingest.parseRunId());

        CellMeaning amount = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B3");
        List<InterpretationEvidence> cols = evidence(amount, EvidenceRole.COLUMN_HEADER);
        assertThat(cols).hasSizeGreaterThanOrEqualTo(2);
        assertThat(cols)
                .allMatch(e -> EvidenceResolution.RESOLVED.equals(e.resolution()))
                .allMatch(e -> e.sourceCellId() != null);
        assertThat(cols.get(0).sourceText()).isEqualTo("Projected");
        assertThat(cols.get(cols.size() - 1).sourceText()).isEqualTo("Amount");

        Path yearXlsx = numericYearWorkbook();
        Path yearDb = tempDir.resolve("year.db");
        IngestSummary yearIngest = new IngestService().ingest(yearXlsx, 1L, yearDb);
        new DiscoverService().discover(yearDb, yearIngest.parseRunId());
        new ClassifyService(bindingLlm("B2")).classify(yearDb, yearIngest.parseRunId());

        CellMeaning yearAmount =
                new CellMeaningService().lookup(yearDb, yearIngest.parseRunId(), "Costs!B2");
        List<InterpretationEvidence> yearCols = evidence(yearAmount, EvidenceRole.COLUMN_HEADER);
        assertThat(yearCols).isNotEmpty();
        assertThat(yearCols.get(yearCols.size() - 1).sourceText()).isEqualTo("2027");
        assertThat(yearCols.get(yearCols.size() - 1).sourceCellId()).isNotNull();
        assertThat(evidence(yearAmount, EvidenceRole.PERIOD))
                .anyMatch(e -> "2027".equals(e.sourceText())
                        && EvidenceResolution.RESOLVED.equals(e.resolution())
                        && "2027".equals(e.normalizedValue()));
    }

    @Test
    void adjacentSchedulesDoNotLeakHeaders() throws Exception {
        Path xlsx = adjacentSchedulesWorkbook();
        Path db = tempDir.resolve("adjacent.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(bindingLlm("B2")).classify(db, ingest.parseRunId());

        CellMeaning left = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B2");
        assertThat(evidence(left, EvidenceRole.ROW_HEADER))
                .anyMatch(e -> "Civil Works".equals(e.sourceText())
                        && EvidenceResolution.RESOLVED.equals(e.resolution()));
        assertThat(evidence(left, EvidenceRole.COLUMN_HEADER))
                .anyMatch(e -> "Amount".equals(e.sourceText())
                        && EvidenceResolution.RESOLVED.equals(e.resolution()));
        assertThat(evidence(left, EvidenceRole.ROW_HEADER))
                .noneMatch(e -> "Glass".equals(e.sourceText()));
        assertThat(evidence(left, EvidenceRole.COLUMN_HEADER))
                .noneMatch(e -> "Amt".equals(e.sourceText()) || "Other".equals(e.sourceText()));

        CellMeaning right = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!F2");
        assertThat(evidence(right, EvidenceRole.ROW_HEADER))
                .anyMatch(e -> "Glass".equals(e.sourceText())
                        && EvidenceResolution.RESOLVED.equals(e.resolution()));
        assertThat(evidence(right, EvidenceRole.COLUMN_HEADER))
                .anyMatch(e -> "Amt".equals(e.sourceText())
                        && EvidenceResolution.RESOLVED.equals(e.resolution()));
        assertThat(evidence(right, EvidenceRole.ROW_HEADER))
                .noneMatch(e -> "Civil Works".equals(e.sourceText()));
        assertThat(evidence(right, EvidenceRole.COLUMN_HEADER))
                .noneMatch(e -> "Amount".equals(e.sourceText()));
    }

    @Test
    void periodBasisCurrencyScaleUnitSurviveWithoutDoubleScaling() throws Exception {
        Path xlsx = contextWorkbook();
        Path db = tempDir.resolve("context.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(bindingLlm("B3")).classify(db, ingest.parseRunId());

        CellMeaning amount = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B3");
        assertThat(amount.interpretation().resultingValue()).contains("0.12");

        assertThat(evidence(amount, EvidenceRole.PERIOD))
                .anyMatch(e -> e.sourceText().toLowerCase().contains("year")
                        && e.normalizedValue() == null
                        && EvidenceResolution.RESOLVED.equals(e.resolution()));
        assertThat(evidence(amount, EvidenceRole.BASIS))
                .anyMatch(e -> e.sourceText().equalsIgnoreCase("Projected")
                        && EvidenceResolution.RESOLVED.equals(e.resolution()));
        assertThat(evidence(amount, EvidenceRole.CURRENCY))
                .anyMatch(e -> EvidenceResolution.RESOLVED.equals(e.resolution())
                        && "INR".equals(e.normalizedValue()));
        assertThat(evidence(amount, EvidenceRole.SCALE))
                .anyMatch(e -> e.sourceText().toLowerCase().contains("lakh")
                        && EvidenceResolution.RESOLVED.equals(e.resolution()));
        // Scale is evidence only — resulting_value stays the workbook magnitude.
        assertThat(amount.interpretation().resultingValue()).doesNotContain("12000");
    }

    @Test
    void mergedColumnHeaderResolvesFromAnchorScope() throws Exception {
        Path xlsx = mergedHeaderWorkbook();
        Path db = tempDir.resolve("merged-header.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(bindingLlm("B3")).classify(db, ingest.parseRunId());

        CellMeaning amount = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B3");
        assertThat(evidence(amount, EvidenceRole.COLUMN_HEADER))
                .anyMatch(e -> "Projected".equals(e.sourceText())
                        && EvidenceResolution.RESOLVED.equals(e.resolution())
                        && e.sourceCellId() != null);
    }

    @Test
    void conflictingSameAreaCandidatesStayAmbiguous() throws Exception {
        Path xlsx = conflictWorkbook();
        Path db = tempDir.resolve("ambiguous.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        try (var workspace = com.resurgent.tev.parser.db.WorkspaceDatabase.open(db)) {
            var repo = new com.resurgent.tev.parser.db.WorkspaceRepository(workspace.connection());
            long worksheetId = repo.selectWorksheetsForParseRun(ingest.parseRunId()).get(0).worksheetId();
            var coverage = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .findFirst()
                    .orElseThrow();
            // Drop discover locals so seeded equal-area peers are the narrowest owners.
            for (var candidate : repo.selectCandidatesForParseRun(ingest.parseRunId())) {
                if (!"coverage_parent".equals(candidate.candidateKind())) {
                    try (var ps = workspace.connection().prepareStatement(
                            "DELETE FROM candidate WHERE candidate_id = ?")) {
                        ps.setLong(1, candidate.candidateId());
                        ps.executeUpdate();
                    }
                }
            }
            long a3 = cellId(db, "Costs", "A3");
            long b1 = cellId(db, "Costs", "B1");
            long b2 = cellId(db, "Costs", "B2");
            long b3 = cellId(db, "Costs", "B3");
            repo.insertCandidate(
                    new com.resurgent.tev.parser.db.CandidateWrite(
                            ingest.parseRunId(),
                            worksheetId,
                            "overlap",
                            coverage.candidateId(),
                            1,
                            1,
                            3,
                            2,
                            null,
                            null,
                            null,
                            false,
                            0.5,
                            "header-a",
                            "seeded amount header"),
                    List.of(a3, b1, b3));
            repo.insertCandidate(
                    new com.resurgent.tev.parser.db.CandidateWrite(
                            ingest.parseRunId(),
                            worksheetId,
                            "overlap",
                            coverage.candidateId(),
                            1,
                            1,
                            3,
                            2,
                            null,
                            null,
                            null,
                            false,
                            0.5,
                            "header-b",
                            "seeded alt header"),
                    List.of(a3, b2, b3));
        }

        new ClassifyService(bindingLlm("B3")).classify(db, ingest.parseRunId());

        CellMeaning amount = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B3");
        List<InterpretationEvidence> cols = evidence(amount, EvidenceRole.COLUMN_HEADER);
        assertThat(cols)
                .isNotEmpty()
                .allMatch(e -> EvidenceResolution.AMBIGUOUS.equals(e.resolution()));
        assertThat(cols.stream().map(InterpretationEvidence::sourceText).toList())
                .contains("Amount", "Alt Header");
    }

    private Path conflictWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row top = sheet.createRow(0);
            top.createCell(0).setCellValue("Item");
            top.createCell(1).setCellValue("Amount");
            Row mid = sheet.createRow(1);
            mid.createCell(0).setCellValue("");
            mid.createCell(1).setCellValue("Alt Header");
            Row body = sheet.createRow(2);
            body.createCell(0).setCellValue("Civil Works");
            body.createCell(1).setCellValue(100.0);
            return writeWorkbook(workbook, "conflict.xlsx");
        }
    }

    private static long cellId(Path db, String sheet, String coord) throws Exception {
        try (var workspace = com.resurgent.tev.parser.db.WorkspaceDatabase.open(db);
                var ps = workspace.connection().prepareStatement(
                        "SELECT c.cell_id FROM cell c"
                                + " JOIN worksheet w ON w.worksheet_id = c.worksheet_id"
                                + " WHERE w.sheet_name = ? AND UPPER(c.coord) = ?"
                                + " ORDER BY w.parse_run_id LIMIT 1")) {
            ps.setString(1, sheet);
            ps.setString(2, coord.toUpperCase());
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    private static List<InterpretationEvidence> evidence(CellMeaning meaning, String role) {
        return meaning.evidence().stream().filter(e -> role.equals(e.role())).toList();
    }

    private static ClassifyServiceTest.FakeClassifierLlm bindingLlm(String coord) {
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm();
        llm.layerBFactory = prompt -> prompt.packet().cells().stream()
                .filter(cell -> coord.equalsIgnoreCase(cell.coord()))
                .findFirst()
                .map(cell -> List.of(new LayerBLineJudgment(
                        cell.coord(),
                        "Air Conditioning",
                        AC_PATH,
                        AmountRole.ADD,
                        List.of(),
                        null,
                        List.of())))
                .orElse(List.of());
        return llm;
    }

    private Path simpleGridWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            Row body = sheet.createRow(1);
            body.createCell(0).setCellValue("Civil Works");
            body.createCell(1).setCellValue(100.0);
            return writeWorkbook(workbook, "simple.xlsx");
        }
    }

    private Path multiLevelWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row top = sheet.createRow(0);
            top.createCell(0).setCellValue("Item");
            top.createCell(1).setCellValue("Projected");
            Row mid = sheet.createRow(1);
            mid.createCell(0).setCellValue("");
            mid.createCell(1).setCellValue("Amount");
            Row body = sheet.createRow(2);
            body.createCell(0).setCellValue("Civil Works");
            body.createCell(1).setCellValue(150.0);
            return writeWorkbook(workbook, "multilevel.xlsx");
        }
    }

    private Path numericYearWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue(2027);
            Row body = sheet.createRow(1);
            body.createCell(0).setCellValue("Civil Works");
            body.createCell(1).setCellValue(100.0);
            return writeWorkbook(workbook, "year.xlsx");
        }
    }

    private Path adjacentSchedulesWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row body = sheet.createRow(1);
            body.createCell(0).setCellValue("Civil Works");
            body.createCell(1).setCellValue(100.0);
            body.createCell(4).setCellValue("Glass");
            body.createCell(5).setCellValue(20.0);
            return writeWorkbook(workbook, "adjacent.xlsx");
        }
    }

    private Path contextWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row top = sheet.createRow(0);
            top.createCell(0).setCellValue("Item");
            top.createCell(1).setCellValue("Projected");
            Row mid = sheet.createRow(1);
            mid.createCell(0).setCellValue("");
            mid.createCell(1).setCellValue("Year 2 (INR lakh)");
            Row body = sheet.createRow(2);
            body.createCell(0).setCellValue("Civil Works");
            body.createCell(1).setCellValue(0.12);
            return writeWorkbook(workbook, "context.xlsx");
        }
    }

    private Path mergedHeaderWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row top = sheet.createRow(0);
            top.createCell(0).setCellValue("Item");
            top.createCell(1).setCellValue("Projected");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 1, 2)); // B1:C1
            Row mid = sheet.createRow(1);
            mid.createCell(0).setCellValue("");
            mid.createCell(1).setCellValue("Amount");
            mid.createCell(2).setCellValue("Qty");
            Row body = sheet.createRow(2);
            body.createCell(0).setCellValue("Civil Works");
            body.createCell(1).setCellValue(150.0);
            body.createCell(2).setCellValue(2.0);
            return writeWorkbook(workbook, "merged-header.xlsx");
        }
    }

    private Path writeWorkbook(XSSFWorkbook workbook, String name) throws Exception {
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        workbook.close();
        return file;
    }
}
