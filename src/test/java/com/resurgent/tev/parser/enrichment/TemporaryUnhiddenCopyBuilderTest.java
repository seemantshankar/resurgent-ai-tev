package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporaryUnhiddenCopyBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void buildUnhidesOnlyTheNamedSheetAndLeavesTheSourceUnchanged() throws Exception {
        Path source = tempDir.resolve("redacted.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet target = workbook.createSheet("Assumptions");
            Row hiddenRow = target.createRow(4);
            hiddenRow.setZeroHeight(true);
            hiddenRow.createCell(2).setCellValue("Backup");
            target.setColumnHidden(2, true);

            Sheet other = workbook.createSheet("Debt");
            other.createRow(6).setZeroHeight(true);
            other.setColumnHidden(5, true);

            try (OutputStream output = Files.newOutputStream(source)) {
                workbook.write(output);
            }
        }
        byte[] originalBytes = Files.readAllBytes(source);

        Path copy = new TemporaryUnhiddenCopyBuilder().build(source, "Assumptions");

        assertThat(copy).exists().isNotEqualTo(source);
        assertThat(Files.readAllBytes(source)).isEqualTo(originalBytes);
        try (XSSFWorkbook workbook = new XSSFWorkbook(copy.toFile())) {
            Sheet target = workbook.getSheet("Assumptions");
            assertThat(target.getRow(4).getZeroHeight()).isFalse();
            assertThat(target.isColumnHidden(2)).isFalse();

            Sheet other = workbook.getSheet("Debt");
            assertThat(other.getRow(6).getZeroHeight()).isTrue();
            assertThat(other.isColumnHidden(5)).isTrue();
        } finally {
            Files.deleteIfExists(copy);
        }
    }
}
