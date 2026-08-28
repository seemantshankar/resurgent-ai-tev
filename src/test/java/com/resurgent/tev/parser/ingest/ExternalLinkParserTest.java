package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileOutputStream;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for external-link discovery during xlsx ingest. */
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
            assertThat(link.sheetNames()).isNotNull();
        }
    }

    @Test
    void adapterPreservesExternalFormulaText() throws Exception {
        try (XSSFWorkbook external = new XSSFWorkbook();
                XSSFWorkbook main = new XSSFWorkbook()) {
            external.createSheet("Other");
            Sheet sheet = main.createSheet("Sheet1");
            main.linkExternalWorkbook("other.xlsx", external);
            sheet.createRow(0).createCell(0).setCellFormula("[1]Other!A1");

            Path xlsx = writeWorkbook(main, "cell-extern.xlsx");
            NormalizedCell a1 = new XlsxAdapter().parse(xlsx).get(0).cells().get(0);
            assertThat(a1.formulaText()).isEqualTo("[1]Other!A1");
        }
    }
}
