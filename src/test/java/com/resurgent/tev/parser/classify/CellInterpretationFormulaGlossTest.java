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
import java.util.concurrent.atomic.AtomicReference;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Primary seam: ingest → discover → classify → cell-meaning for #119 formula gloss. */
class CellInterpretationFormulaGlossTest {

    private static final String AC_PATH = "Project Cost > Plant & Machinery > Air Conditioning";
    private static final String CIVIL_PATH = "Project Cost > Civil Works > Structure";
    private static final String GLOSS =
            "Sum of Air Conditioning and Civil Works Structure amounts for the period.";

    @TempDir
    Path tempDir;

    @Test
    void formulaReadbackIncludesSeparatelyStoredGlossWhenAnnotationExists() throws Exception {
        Path xlsx = sumOfTwoCellsWorkbook();
        Path db = tempDir.resolve("gloss.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeFormulaGlossLlm glossLlm = new FakeFormulaGlossLlm(GLOSS);
        new ClassifyService(
                        bindingPaths(Map.of("B2", AC_PATH, "B3", CIVIL_PATH)),
                        new DiscoverService(),
                        glossLlm)
                .classify(db, ingest.parseRunId());

        CellMeaning total = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B4");
        assertThat(total.interpretation()).isNotNull();
        assertThat(total.interpretation().formulaText()).isEqualTo("B2+B3");
        assertThat(total.formulaAnnotations()).hasSize(2);
        assertThat(total.formulaGloss()).isEqualTo(GLOSS);
        assertThat(glossLlm.prompts).hasSize(1);
    }

    @Test
    void llmInputIsNumberRedactedWithoutExpandedRealAmounts() throws Exception {
        Path xlsx = sumOfTwoCellsWorkbook();
        Path db = tempDir.resolve("redacted.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        AtomicReference<FormulaGlossPrompt> seen = new AtomicReference<>();
        FormulaGlossLlm glossLlm = prompt -> {
            seen.set(prompt);
            return GLOSS;
        };
        new ClassifyService(
                        bindingPaths(Map.of("B2", AC_PATH, "B3", CIVIL_PATH)),
                        new DiscoverService(),
                        glossLlm)
                .classify(db, ingest.parseRunId());

        FormulaGlossPrompt prompt = seen.get();
        assertThat(prompt).isNotNull();
        assertThat(prompt.formulaText()).isEqualTo("B2+B3");
        String serialized = FormulaGlossPromptAssembler.userMessage(prompt);
        assertThat(serialized).doesNotContain("100");
        assertThat(serialized).doesNotContain("40");
        assertThat(serialized).doesNotContain("140");
        assertThat(serialized).doesNotContain("resultingValue");
        assertThat(serialized).doesNotContain("cached");
        assertThat(serialized).contains("B2");
        assertThat(serialized).contains("B3");
        assertThat(serialized).contains("Total");
        assertThat(serialized).contains("Amount");
        assertThat(prompt.annotations()).isNotEmpty();
    }

    @Test
    void glossDoesNotAlterSourceFormulaFactsOrAnnotations() throws Exception {
        Path xlsx = sumOfTwoCellsWorkbook();
        Path db = tempDir.resolve("immutable.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        new ClassifyService(
                        bindingPaths(Map.of("B2", AC_PATH, "B3", CIVIL_PATH)),
                        new DiscoverService(),
                        new FakeFormulaGlossLlm(GLOSS))
                .classify(db, ingest.parseRunId());

        CellMeaning total = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B4");
        assertThat(total.interpretation().formulaText()).isEqualTo("B2+B3");
        assertThat(total.interpretation().valueOrigin()).isEqualTo("formula");
        assertThat(total.formulaAnnotations()).hasSize(2);
        assertThat(total.formulaAnnotations().get(0).rawToken()).isEqualToIgnoringCase("B2");
        assertThat(total.formulaAnnotations().get(1).rawToken()).isEqualToIgnoringCase("B3");
        assertThat(total.formulaGloss()).isEqualTo(GLOSS);
    }

    @Test
    void literalCellsAndMissingAnnotationsGetNoGloss() throws Exception {
        Path xlsx = sumOfTwoCellsWorkbook();
        Path db = tempDir.resolve("no-gloss.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeFormulaGlossLlm glossLlm = new FakeFormulaGlossLlm(GLOSS);
        new ClassifyService(
                        bindingPaths(Map.of("B2", AC_PATH, "B3", CIVIL_PATH)),
                        new DiscoverService(),
                        glossLlm)
                .classify(db, ingest.parseRunId());

        CellMeaning literal = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B2");
        assertThat(literal.interpretation().valueOrigin()).isEqualTo("literal");
        assertThat(literal.formulaAnnotations()).isEmpty();
        assertThat(literal.formulaGloss()).isNull();
    }

    @Test
    void unconfiguredGlossPortLeavesGlossAbsentWithoutFailingClassify() throws Exception {
        Path xlsx = sumOfTwoCellsWorkbook();
        Path db = tempDir.resolve("noop.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        new ClassifyService(bindingPaths(Map.of("B2", AC_PATH, "B3", CIVIL_PATH)))
                .classify(db, ingest.parseRunId());

        CellMeaning total = new CellMeaningService().lookup(db, ingest.parseRunId(), "Costs!B4");
        assertThat(total.formulaAnnotations()).isNotEmpty();
        assertThat(total.formulaGloss()).isNull();
    }

    private static ClassifyServiceTest.FakeClassifierLlm bindingPaths(Map<String, String> paths) {
        ClassifyServiceTest.FakeClassifierLlm llm = new ClassifyServiceTest.FakeClassifierLlm();
        llm.layerBFactory = prompt -> {
            List<LayerBLineJudgment> lines = new ArrayList<>();
            for (var cell : prompt.packet().cells()) {
                String path = paths.get(cell.coord());
                if (path == null) {
                    continue;
                }
                lines.add(new LayerBLineJudgment(
                        cell.coord(),
                        cell.textValue() != null ? cell.textValue() : cell.coord(),
                        path,
                        AmountRole.ADD,
                        List.of(),
                        0.9));
            }
            return lines;
        };
        return llm;
    }

    private Path sumOfTwoCellsWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            Row ac = sheet.createRow(1);
            ac.createCell(0).setCellValue("Air Conditioning");
            ac.createCell(1).setCellValue(100.0);
            Row civil = sheet.createRow(2);
            civil.createCell(0).setCellValue("Civil Works");
            civil.createCell(1).setCellValue(40.0);
            Row total = sheet.createRow(3);
            total.createCell(0).setCellValue("Total");
            total.createCell(1).setCellFormula("B2+B3");
            Path path = tempDir.resolve("sum.xlsx");
            try (FileOutputStream out = new FileOutputStream(path.toFile())) {
                workbook.write(out);
            }
            return path;
        }
    }

    static final class FakeFormulaGlossLlm implements FormulaGlossLlm {
        final List<FormulaGlossPrompt> prompts = new ArrayList<>();
        private final String gloss;

        FakeFormulaGlossLlm(String gloss) {
            this.gloss = gloss;
        }

        @Override
        public String gloss(FormulaGlossPrompt prompt) {
            prompts.add(prompt);
            return gloss;
        }
    }
}
