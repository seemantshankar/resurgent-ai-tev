package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Primary seam: ingest → discover → classify → cell-meaning for #118 formula annotation. */
class CellInterpretationFormulaAnnotationTest {

    private static final String AC_PATH = "Project Cost > Plant & Machinery > Air Conditioning";
    private static final String CIVIL_PATH = "Project Cost > Civil Works > Structure";

    @TempDir
    Path tempDir;

    @Test
    void formulaReadbackIncludesExpressionAndOrderedAnnotatedReferences() throws Exception {
        Path xlsx = sumOfTwoCellsWorkbook();
        Path db = tempDir.resolve("ordered.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(bindingPaths(Map.of("B2", AC_PATH, "B3", CIVIL_PATH)))
                .classify(db, ingest.parseRunId());

        CellMeaning total = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B4");
        assertThat(total.interpretation()).isNotNull();
        assertThat(total.interpretation().formulaText()).isEqualTo("B2+B3");
        assertThat(total.interpretation().valueOrigin()).isEqualTo("formula");

        List<FormulaAnnotation> annotations = total.formulaAnnotations();
        assertThat(annotations).hasSize(2);
        assertThat(annotations.get(0).ordinal()).isZero();
        assertThat(annotations.get(0).rawToken()).isEqualToIgnoringCase("B2");
        assertThat(annotations.get(0).completeness()).isEqualTo(AnnotationCompleteness.COMPLETE);
        assertThat(annotations.get(0).members()).hasSize(1);
        assertThat(annotations.get(0).members().get(0).coord()).isEqualToIgnoringCase("B2");
        assertThat(annotations.get(0).members().get(0).nomenclaturePath()).isEqualTo(AC_PATH);

        assertThat(annotations.get(1).ordinal()).isEqualTo(1);
        assertThat(annotations.get(1).rawToken()).isEqualToIgnoringCase("B3");
        assertThat(annotations.get(1).members().get(0).coord()).isEqualToIgnoringCase("B3");
        assertThat(annotations.get(1).members().get(0).nomenclaturePath()).isEqualTo(CIVIL_PATH);

        Path beforeDb = tempDir.resolve("before.db");
        IngestSummary before = new IngestService().ingest(xlsx, 1L, beforeDb);
        new DiscoverService().discover(beforeDb, before.parseRunId());
        CellMeaning unclassified =
                new CellMeaningService().lookup(beforeDb, before.parseRunId(), "Costs!B4");
        assertThat(unclassified.interpretation()).isNull();
        assertThat(unclassified.formulaAnnotations()).isEmpty();
    }

    @Test
    void boundedRangeReportsCompletenessWithoutInventingBlankCells() throws Exception {
        Path xlsx = sparseRangeWorkbook();
        Path db = tempDir.resolve("sparse.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(bindingPaths(Map.of("B2", AC_PATH, "B4", CIVIL_PATH)))
                .classify(db, ingest.parseRunId());

        CellMeaning total = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B5");
        assertThat(total.interpretation().formulaText()).containsIgnoringCase("SUM(B2:B4)");
        assertThat(total.formulaAnnotations()).hasSize(1);

        FormulaAnnotation range = total.formulaAnnotations().get(0);
        assertThat(range.rawToken()).containsIgnoringCase("B2:B4");
        assertThat(range.enclosingFunction()).isEqualToIgnoringCase("SUM");
        assertThat(range.completeness()).isEqualTo(AnnotationCompleteness.INCOMPLETE);
        // B2 and B4 exist; B3 was never persisted — do not invent it.
        assertThat(range.members()).hasSize(2);
        assertThat(range.members().stream().map(FormulaAnnotationMember::coord).toList())
                .containsExactlyInAnyOrder("B2", "B4");
        assertThat(range.members()).noneMatch(m -> "B3".equalsIgnoreCase(m.coord()));
    }

    @Test
    void sumVersusAveragePreservedAndSharedHeadIsDescriptiveOnly() throws Exception {
        Path sumDb = tempDir.resolve("sum.db");
        IngestSummary sumIngest = new IngestService().ingest(aggregateWorkbook("SUM"), 1L, sumDb);
        new DiscoverService().discover(sumDb, sumIngest.parseRunId());
        new ClassifyService(bindingPaths(Map.of("B2", AC_PATH, "B3", AC_PATH)))
                .classify(sumDb, sumIngest.parseRunId());

        Path avgDb = tempDir.resolve("avg.db");
        IngestSummary avgIngest = new IngestService().ingest(aggregateWorkbook("AVERAGE"), 1L, avgDb);
        new DiscoverService().discover(avgDb, avgIngest.parseRunId());
        new ClassifyService(bindingPaths(Map.of("B2", AC_PATH, "B3", AC_PATH)))
                .classify(avgDb, avgIngest.parseRunId());

        CellMeaning sum = new CellMeaningService().lookup(sumDb, sumIngest.parseRunId(), "Costs!B4");
        CellMeaning avg = new CellMeaningService().lookup(avgDb, avgIngest.parseRunId(), "Costs!B4");

        assertThat(sum.interpretation().formulaText()).contains("SUM");
        assertThat(avg.interpretation().formulaText()).contains("AVERAGE");
        assertThat(sum.interpretation().formulaText())
                .isNotEqualTo(avg.interpretation().formulaText());

        FormulaAnnotation sumAnn = sum.formulaAnnotations().get(0);
        FormulaAnnotation avgAnn = avg.formulaAnnotations().get(0);
        assertThat(sumAnn.enclosingFunction()).isEqualToIgnoringCase("SUM");
        assertThat(avgAnn.enclosingFunction()).isEqualToIgnoringCase("AVERAGE");

        // Shared leaf describes dependencies only — never a valid economic rollup instruction.
        assertThat(sumAnn.sharedDependencyPath()).isEqualTo(AC_PATH);
        assertThat(sumAnn.sharedDependencyKind()).isEqualTo(SharedDependencyKind.LEAF);
        assertThat(sumAnn.assertsEconomicRollup()).isFalse();
        assertThat(avgAnn.assertsEconomicRollup()).isFalse();
    }

    @Test
    void unresolvedAndExternalTargetsStayExplicitWithoutInventedCells() throws Exception {
        Path xlsx = unresolvedAndExternalWorkbook();
        Path db = tempDir.resolve("unresolved.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(new ClassifyServiceTest.FakeClassifierLlm())
                .classify(db, ingest.parseRunId());

        CellMeaning cell = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B2");
        assertThat(cell.interpretation().formulaText()).isNotBlank();
        assertThat(cell.formulaAnnotations()).isNotEmpty();

        assertThat(cell.formulaAnnotations())
                .anyMatch(a -> AnnotationCompleteness.UNRESOLVED.equals(a.completeness())
                        || AnnotationCompleteness.EXTERNAL.equals(a.completeness()));
        assertThat(cell.formulaAnnotations())
                .filteredOn(a -> AnnotationCompleteness.UNRESOLVED.equals(a.completeness())
                        || AnnotationCompleteness.EXTERNAL.equals(a.completeness()))
                .allMatch(a -> a.members().isEmpty());
    }

    @Test
    void annotationLifecycleFollowsInterpretationReplacement() throws Exception {
        Path xlsx = sumOfTwoCellsWorkbook();
        Path db = tempDir.resolve("lifecycle.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(bindingPaths(Map.of("B2", AC_PATH, "B3", CIVIL_PATH)))
                .classify(db, ingest.parseRunId());

        CellMeaning first = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B4");
        assertThat(first.formulaAnnotations()).hasSize(2);
        assertThat(first.formulaAnnotations().get(0).members().get(0).nomenclaturePath())
                .isEqualTo(AC_PATH);

        new ClassifyService(bindingPaths(Map.of("B2", CIVIL_PATH, "B3", CIVIL_PATH)))
                .classify(db, ingest.parseRunId());

        CellMeaning second = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B4");
        assertThat(second.formulaAnnotations()).hasSize(2);
        assertThat(second.formulaAnnotations())
                .allMatch(a -> a.members().stream()
                        .allMatch(m -> CIVIL_PATH.equals(m.nomenclaturePath())));

        new DiscoverService().discover(db, ingest.parseRunId());
        CellMeaning afterRediscover =
                new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B4");
        assertThat(afterRediscover.interpretation()).isNull();
        assertThat(afterRediscover.formulaAnnotations()).isEmpty();
    }

    private Path sumOfTwoCellsWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row r2 = sheet.createRow(1);
            r2.createCell(0).setCellValue("Air Conditioning");
            r2.createCell(1).setCellValue(10.0);
            r2.createCell(4).setCellValue("Glass");
            r2.createCell(5).setCellValue(1.0);
            Row r3 = sheet.createRow(2);
            r3.createCell(0).setCellValue("Structure");
            r3.createCell(1).setCellValue(20.0);
            r3.createCell(4).setCellValue("Paint");
            r3.createCell(5).setCellValue(2.0);
            Row r4 = sheet.createRow(3);
            r4.createCell(0).setCellValue("Total");
            r4.createCell(1).setCellFormula("B2+B3");
            r4.createCell(4).setCellValue("Misc");
            r4.createCell(5).setCellValue(3.0);
            return writeWorkbook(workbook, "sum-two.xlsx");
        }
    }

    private Path sparseRangeWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row r2 = sheet.createRow(1);
            r2.createCell(0).setCellValue("Air Conditioning");
            r2.createCell(1).setCellValue(10.0);
            r2.createCell(4).setCellValue("Glass");
            r2.createCell(5).setCellValue(1.0);
            // Row 3 (B3) intentionally omitted on the left — blank, not persisted.
            Row r4 = sheet.createRow(3);
            r4.createCell(0).setCellValue("Structure");
            r4.createCell(1).setCellValue(20.0);
            r4.createCell(4).setCellValue("Paint");
            r4.createCell(5).setCellValue(2.0);
            Row r5 = sheet.createRow(4);
            r5.createCell(0).setCellValue("Total");
            r5.createCell(1).setCellFormula("SUM(B2:B4)");
            r5.createCell(4).setCellValue("Misc");
            r5.createCell(5).setCellValue(3.0);
            return writeWorkbook(workbook, "sparse-range.xlsx");
        }
    }

    private Path aggregateWorkbook(String function) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row r2 = sheet.createRow(1);
            r2.createCell(0).setCellValue("Air Conditioning");
            r2.createCell(1).setCellValue(10.0);
            r2.createCell(4).setCellValue("Glass");
            r2.createCell(5).setCellValue(1.0);
            Row r3 = sheet.createRow(2);
            r3.createCell(0).setCellValue("Air Conditioning");
            r3.createCell(1).setCellValue(20.0);
            r3.createCell(4).setCellValue("Paint");
            r3.createCell(5).setCellValue(2.0);
            Row r4 = sheet.createRow(3);
            r4.createCell(0).setCellValue("Total");
            r4.createCell(1).setCellFormula(function + "(B2:B3)");
            r4.createCell(4).setCellValue("Misc");
            r4.createCell(5).setCellValue(3.0);
            return writeWorkbook(workbook, function.toLowerCase() + "-agg.xlsx");
        }
    }

    private Path unresolvedAndExternalWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row body = sheet.createRow(1);
            body.createCell(0).setCellValue("External");
            body.createCell(1).setCellFormula("[1]Other!A1");
            body.createCell(4).setCellValue("Glass");
            body.createCell(5).setCellValue(1.0);
            return writeWorkbook(workbook, "external.xlsx");
        }
    }

    private static ClassifyServiceTest.FakeClassifierLlm bindingPaths(Map<String, String> paths) {
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm();
        llm.layerBFactory = prompt -> {
            ArrayList<LayerBLineJudgment> out = new ArrayList<>();
            for (Map.Entry<String, String> entry : paths.entrySet()) {
                prompt.packet().cells().stream()
                        .filter(cell -> entry.getKey().equalsIgnoreCase(cell.coord()))
                        .findFirst()
                        .ifPresent(cell -> out.add(new LayerBLineJudgment(
                                cell.coord(),
                                leafName(entry.getValue()),
                                entry.getValue(),
                                AmountRole.ADD,
                                List.of(),
                                null,
                                List.of())));
            }
            return out;
        };
        return llm;
    }

    private static String leafName(String path) {
        int idx = path.lastIndexOf('>');
        return idx < 0 ? path : path.substring(idx + 1).trim();
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
