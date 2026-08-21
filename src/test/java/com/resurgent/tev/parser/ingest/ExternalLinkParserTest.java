package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for external-link discovery and formula reference extraction.
 * All fixtures are generated in-memory with Apache POI so no network access is
 * required.
 */
class ExternalLinkParserTest {

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
    void parserDiscoversLinkedExternalWorkbook() throws Exception {
        try (XSSFWorkbook external = new XSSFWorkbook();
                XSSFWorkbook main = new XSSFWorkbook()) {
            external.createSheet("Other");
            Sheet sheet = main.createSheet("Sheet1");
            main.linkExternalWorkbook("other.xlsx", external);
            Row row = sheet.createRow(0);
            row.createCell(0).setCellFormula("[1]Other!A1");

            Path xlsx = writeWorkbook(main, "linked.xlsx");
            XlsxWorkbook result = new XlsxAdapter().parseWorkbook(xlsx);

            assertThat(result.metadata().externalLinks()).hasSize(1);
            ExternalLinkIn link = result.metadata().externalLinks().get(0);
            assertThat(link.linkIndex()).isEqualTo(1);
            assertThat(link.targetUri()).isEqualTo("other.xlsx");
            assertThat(link.targetDisplay()).isEqualTo("other.xlsx");
            assertThat(link.rawPartName()).isEqualTo("/xl/externalLinks/externalLink1.xml");
            // POI does not cache external sheet names in the generated fixture, so
            // sheetNames may be empty; we only assert the field is non-null.
            assertThat(link.sheetNames()).isNotNull();
        }
    }

    @Test
    void formulaReferenceExtractorExtractsExternalAndDefinedNameRefs() {
        FormulaReferences refs = FormulaReferenceExtractor.extract(
                "=[1]Other!A1+ReferencedName", List.of("ReferencedName", "UnusedName"));

        assertThat(refs.externalRefs()).containsExactly("[1]Other!A1");
        assertThat(refs.definedNameRefs()).containsExactly("ReferencedName");
    }

    @Test
    void formulaReferenceExtractorHandlesQuotedExternalSheet() {
        FormulaReferences refs = FormulaReferenceExtractor.extract(
                "='[1]Other Sheet'!A1+B1", List.of());

        assertThat(refs.externalRefs()).containsExactly("'[1]Other Sheet'!A1");
    }

    @Test
    void formulaReferenceExtractorHandlesExternalDefinedName() {
        FormulaReferences refs = FormulaReferenceExtractor.extract(
                "=[1]!MyName", List.of());

        assertThat(refs.externalRefs()).containsExactly("[1]!MyName");
    }

    @Test
    void adapterPopulatesExternalRefOnFormulaCell() throws Exception {
        try (XSSFWorkbook external = new XSSFWorkbook();
                XSSFWorkbook main = new XSSFWorkbook()) {
            external.createSheet("Other");
            Sheet sheet = main.createSheet("Sheet1");
            main.linkExternalWorkbook("other.xlsx", external);
            sheet.createRow(0).createCell(0).setCellFormula("[1]Other!A1");

            Path xlsx = writeWorkbook(main, "cell-extern.xlsx");
            List<XlsxSheet> sheets = new XlsxAdapter().parse(xlsx);

            NormalizedCell a1 = sheets.get(0).cells().get(0);
            assertThat(a1.formulaText()).isEqualTo("[1]Other!A1");
            assertThat(a1.externalRef()).isEqualTo("[1]Other!A1");
        }
    }
}
