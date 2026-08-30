package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorksheetSnapshotReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsFilledCellsAndSameSheetFormulaRanges() throws Exception {
        Path workbookPath = tempDir.resolve("formula.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Project Cost");
            sheet.createRow(0).createCell(0).setCellValue("Total");
            sheet.createRow(1).createCell(1).setCellFormula("SUM(E1:F2)+'Other Tab'!A1");
            workbook.createSheet("Other Tab").createRow(0).createCell(0).setCellValue(1);
            try (OutputStream output = Files.newOutputStream(workbookPath)) {
                workbook.write(output);
            }
        }

        WorksheetSnapshot snapshot =
                new WorksheetSnapshotReader().read(workbookPath, "Project Cost");

        assertThat(snapshot.filledCells()).containsExactlyInAnyOrder("A1", "B2");
        assertThat(snapshot.formulaReferences().get("B2"))
                .containsExactlyInAnyOrder("E1", "F1", "E2", "F2");
    }
}
