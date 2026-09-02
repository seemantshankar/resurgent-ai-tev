package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorksheetEnrichmentViewBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsSparseGridCellIndexAndIslands() throws Exception {
        Path workbook = tempDir.resolve("grid.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Project Cost");
            sheet.createRow(0).createCell(0).setCellValue("Revenue");
            var row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("Rooms");
            row1.createCell(1).setCellValue(100.0);
            row1.createCell(2).setCellValue(80.0);
            var scratch = sheet.createRow(3);
            scratch.createCell(5).setCellValue(999.0);
            try (OutputStream out = Files.newOutputStream(workbook)) {
                wb.write(out);
            }
        }

        WorksheetEnrichmentView view =
                new WorksheetEnrichmentViewBuilder().build(workbook, "Project Cost");

        assertThat(view.filledCellCount()).isEqualTo(5);
        assertThat(view.minRow()).isEqualTo(1);
        assertThat(view.maxRow()).isEqualTo(4);
        assertThat(view.columnHeaderLine()).contains("A").contains("F");
        assertThat(view.sparseGrid())
                .contains("Row 1 | A1:Revenue")
                .contains("Row 2 | A2:Rooms | B2:100")
                .contains("F4:999");
        assertThat(view.cellIndexNdjson())
                .contains("\"coord\":\"B2\"")
                .contains("\"kind\":\"amount\"");
        assertThat(view.islands()).hasSize(2);
        assertThat(view.islands().getFirst().bounds()).isEqualTo("A1:C2");
        assertThat(view.islands().get(1).bounds()).isEqualTo("F4");
    }
}
