package com.resurgent.tev.parser.ingest.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.ingest.FileType;
import com.resurgent.tev.parser.ingest.IngestRejectionException;
import com.resurgent.tev.parser.ingest.RejectionReason;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Public-seam tests for {@link SafetyEnforcer}. Fixtures are synthetic workbooks
 * built with Apache POI or raw OOXML bytes so the assertions exercise the real
 * read path without depending on internal helper signatures.
 */
class SafetyEnforcerTest {

    @TempDir
    Path tempDir;

    private final SafetyEnforcer enforcer = new SafetyEnforcer();

    private Path writeWorkbook(Workbook workbook, String name) throws Exception {
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        workbook.close();
        return file;
    }

    private ParserConfig config(int maxSheetCount, int maxRowCount, int maxColumnCount,
            long maxCellCount, int maxZipExpansionRatio) {
        return new ParserConfig(
                10_000_000L,
                maxSheetCount,
                maxRowCount,
                maxColumnCount,
                maxCellCount,
                maxZipExpansionRatio,
                false,
                true,
                true, 3);
    }

    @Test
    void zipBombRejectsWithExpansionRatioExceeded() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Bomb");
            // Excel limits a single cell to 32,767 characters, so spread a large,
            // highly compressible payload across many unique cells. The uncompressed
            // package becomes much larger than the on-disk ZIP.
            for (int r = 0; r < 20; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < 5; c++) {
                    row.createCell(c).setCellValue("A".repeat(32_700) + "-" + r + "-" + c);
                }
            }

            Path xlsx = writeWorkbook(workbook, "bomb.xlsx");
            ParserConfig config = config(10, 100, 100, 1_000_000L, 2);

            assertThatThrownBy(() -> enforcer.check(xlsx, FileType.FM_XLSX, config))
                    .isInstanceOf(IngestRejectionException.class)
                    .satisfies(e -> {
                        IngestRejectionException r = (IngestRejectionException) e;
                        assertThat(r.reason()).isEqualTo(RejectionReason.EXPANSION_RATIO_EXCEEDED);
                        assertThat(r.configuredLimit()).isEqualTo(2);
                        assertThat(r.observedValue()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.DOUBLE)
                                .isGreaterThan(2.0);
                    });
        }
    }

    @Test
    void sheetCountExceededFiresIndependently() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("A");
            workbook.createSheet("B");
            workbook.createSheet("C");

            Path xlsx = writeWorkbook(workbook, "sheets.xlsx");
            ParserConfig config = config(2, 100, 100, 10_000L, 100);

            assertThatThrownBy(() -> enforcer.check(xlsx, FileType.FM_XLSX, config))
                    .isInstanceOf(IngestRejectionException.class)
                    .satisfies(e -> {
                        IngestRejectionException r = (IngestRejectionException) e;
                        assertThat(r.reason()).isEqualTo(RejectionReason.SHEET_COUNT_EXCEEDED);
                        assertThat(r.configuredLimit()).isEqualTo(2);
                        assertThat(r.observedValue()).isEqualTo(3);
                    });
        }
    }

    @Test
    void rowCountExceededFiresIndependently() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Rows");
            sheet.createRow(5); // row extent is 6

            Path xlsx = writeWorkbook(workbook, "rows.xlsx");
            ParserConfig config = config(10, 5, 100, 10_000L, 100);

            assertThatThrownBy(() -> enforcer.check(xlsx, FileType.FM_XLSX, config))
                    .isInstanceOf(IngestRejectionException.class)
                    .satisfies(e -> {
                        IngestRejectionException r = (IngestRejectionException) e;
                        assertThat(r.reason()).isEqualTo(RejectionReason.ROW_COUNT_EXCEEDED);
                        assertThat(r.configuredLimit()).isEqualTo(5);
                        assertThat(r.observedValue()).isEqualTo(6);
                    });
        }
    }

    @Test
    void columnCountExceededFiresIndependently() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Cols");
            Row row = sheet.createRow(0);
            for (int c = 0; c < 6; c++) {
                row.createCell(c).setCellValue(c);
            }

            Path xlsx = writeWorkbook(workbook, "cols.xlsx");
            ParserConfig config = config(10, 100, 5, 10_000L, 100);

            assertThatThrownBy(() -> enforcer.check(xlsx, FileType.FM_XLSX, config))
                    .isInstanceOf(IngestRejectionException.class)
                    .satisfies(e -> {
                        IngestRejectionException r = (IngestRejectionException) e;
                        assertThat(r.reason()).isEqualTo(RejectionReason.COLUMN_COUNT_EXCEEDED);
                        assertThat(r.configuredLimit()).isEqualTo(5);
                        assertThat(r.observedValue()).isEqualTo(6);
                    });
        }
    }

    @Test
    void cellCountExceededFiresIndependently() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet s1 = workbook.createSheet("S1");
            for (int r = 0; r < 3; r++) {
                s1.createRow(r).createCell(0).setCellValue(r);
            }
            Sheet s2 = workbook.createSheet("S2");
            for (int r = 0; r < 3; r++) {
                s2.createRow(r).createCell(0).setCellValue(r);
            }

            Path xlsx = writeWorkbook(workbook, "cells.xlsx");
            ParserConfig config = config(10, 100, 100, 5L, 100);

            assertThatThrownBy(() -> enforcer.check(xlsx, FileType.FM_XLSX, config))
                    .isInstanceOf(IngestRejectionException.class)
                    .satisfies(e -> {
                        IngestRejectionException r = (IngestRejectionException) e;
                        assertThat(r.reason()).isEqualTo(RejectionReason.CELL_COUNT_EXCEEDED);
                        assertThat(r.configuredLimit()).isEqualTo(5L);
                        assertThat(r.observedValue()).isEqualTo(6L);
                    });
        }
    }

    @Test
    void encryptedWorkbookRejectsWithPasswordProtected() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Secret").createRow(0).createCell(0).setCellValue(42);
            Path plain = writeWorkbook(workbook, "plain.xlsx");
            Path encrypted = tempDir.resolve("encrypted.xlsx");
            encrypt(plain, encrypted, "s3cr3t");

            ParserConfig config = ParserConfig.embeddedDefaults();

            assertThatThrownBy(() -> enforcer.check(encrypted, FileType.FM_XLSX, config))
                    .isInstanceOf(IngestRejectionException.class)
                    .satisfies(e -> {
                        IngestRejectionException r = (IngestRejectionException) e;
                        assertThat(r.reason()).isEqualTo(RejectionReason.PASSWORD_PROTECTED);
                    });
        }
    }

    @Test
    void embeddedOleObjectRejectsWithOleObjectRejected() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Ole");
            byte[] oleData = new byte[] {0x01, 0x02, 0x03, 0x04};
            workbook.addOlePackage(oleData, "payload.bin",
                    "00020906-0000-0000-C000-000000000046", "payload.bin");

            Path xlsx = writeWorkbook(workbook, "ole.xlsx");
            ParserConfig config = ParserConfig.embeddedDefaults();

            assertThatThrownBy(() -> enforcer.check(xlsx, FileType.FM_XLSX, config))
                    .isInstanceOf(IngestRejectionException.class)
                    .satisfies(e -> {
                        IngestRejectionException r = (IngestRejectionException) e;
                        assertThat(r.reason()).isEqualTo(RejectionReason.OLE_OBJECT_REJECTED);
                    });
        }
    }

    @Test
    void ddeLinkRejectsWithDdeLinkRejected() throws Exception {
        Path dde = writeDdeWorkbook("dde.xlsx");
        ParserConfig config = ParserConfig.embeddedDefaults();

        assertThatThrownBy(() -> enforcer.check(dde, FileType.FM_XLSX, config))
                .isInstanceOf(IngestRejectionException.class)
                .satisfies(e -> {
                    IngestRejectionException r = (IngestRejectionException) e;
                    assertThat(r.reason()).isEqualTo(RejectionReason.DDE_LINK_REJECTED);
                });
    }

    @Test
    void malformedSheetXmlRejectsWithMalformedPackage() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Bad").createRow(0).createCell(0).setCellValue(1);
            Path base = writeWorkbook(workbook, "base.xlsx");
            Path malformed = corruptSheetXml(base, "malformed.xlsx");

            ParserConfig config = ParserConfig.embeddedDefaults();

            assertThatThrownBy(() -> enforcer.check(malformed, FileType.FM_XLSX, config))
                    .isInstanceOf(IngestRejectionException.class)
                    .satisfies(e -> {
                        IngestRejectionException r = (IngestRejectionException) e;
                        assertThat(r.reason()).isEqualTo(RejectionReason.MALFORMED_PACKAGE);
                    });
        }
    }

    @Test
    void entityExpansionAttemptRejectsWithMalformedPackage() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Xxe").createRow(0).createCell(0).setCellValue(1);
            Path base = writeWorkbook(workbook, "base.xlsx");
            Path xxe = xxeSheetXml(base, "xxe.xlsx");

            ParserConfig config = ParserConfig.embeddedDefaults();

            assertThatThrownBy(() -> enforcer.check(xxe, FileType.FM_XLSX, config))
                    .isInstanceOf(IngestRejectionException.class)
                    .satisfies(e -> {
                        IngestRejectionException r = (IngestRejectionException) e;
                        assertThat(r.reason()).isEqualTo(RejectionReason.MALFORMED_PACKAGE);
                    });
        }
    }

    @Test
    void csvIsNotChecked() throws Exception {
        Path csv = tempDir.resolve("data.csv");
        Files.writeString(csv, "a,b,c\n1,2,3\n");
        ParserConfig config = new ParserConfig(1L, 1, 1, 1, 1L, 1, false, true, true, 3);

        enforcer.check(csv, FileType.FM_CSV, config);

        assertThat(csv).exists();
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private void encrypt(Path plain, Path encrypted, String password) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem();
                FileOutputStream fos = new FileOutputStream(encrypted.toFile())) {
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor enc = info.getEncryptor();
            enc.confirmPassword(password);
            try (OPCPackage opc = OPCPackage.open(plain.toFile(), PackageAccess.READ_WRITE);
                    OutputStream os = enc.getDataStream(fs)) {
                opc.save(os);
            }
            fs.writeFilesystem(fos);
        }
    }

    private Path writeDdeWorkbook(String name) throws Exception {
        Path out = tempDir.resolve(name);
        String mainNs = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        String relNs = "http://schemas.openxmlformats.org/package/2006/relationships";
        String rNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        String worksheetRel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet";
        String workbookRel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";
        String externalLinkRel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/externalLink";

        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/externalLinks/externalLink1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.externalLink+xml\"/>"
                + "</Types>";

        String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"" + relNs + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + workbookRel + "\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";

        String workbookRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"" + relNs + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + worksheetRel + "\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + externalLinkRel + "\" Target=\"externalLinks/externalLink1.xml\"/>"
                + "</Relationships>";

        String workbookXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<workbook xmlns=\"" + mainNs + "\" xmlns:r=\"" + rNs + "\">"
                + "<sheets><sheet name=\"Dde\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "<externalReferences><externalReference r:id=\"rId2\"/></externalReferences>"
                + "</workbook>";

        String sheetXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<worksheet xmlns=\"" + mainNs + "\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>"
                + "</sheetData></worksheet>";

        String ddeXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<externalLink xmlns=\"" + mainNs + "\">"
                + "<ddeLink ddeService=\"excel\" ddeTopic=\"sheet1\">"
                + "<ddeItems><ddeItem name=\"R1C1\" advise=\"1\"/></ddeItems>"
                + "</ddeLink>"
                + "</externalLink>";

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(out.toFile()))) {
            putEntry(zos, "[Content_Types].xml", contentTypes);
            putEntry(zos, "_rels/.rels", rootRels);
            putEntry(zos, "xl/workbook.xml", workbookXml);
            putEntry(zos, "xl/_rels/workbook.xml.rels", workbookRels);
            putEntry(zos, "xl/worksheets/sheet1.xml", sheetXml);
            putEntry(zos, "xl/externalLinks/externalLink1.xml", ddeXml);
        }
        return out;
    }

    private static void putEntry(java.util.zip.ZipOutputStream zos, String name, String content) throws Exception {
        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private Path corruptSheetXml(Path base, String name) throws Exception {
        Path out = tempDir.resolve(name);
        Files.copy(base, out);

        try (OPCPackage pkg = OPCPackage.open(out.toFile(), PackageAccess.READ_WRITE)) {
            for (PackagePart part : pkg.getParts()) {
                String pn = part.getPartName().getName();
                if (pn.startsWith("/xl/worksheets/sheet") && pn.endsWith(".xml")) {
                    String xml = new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    // Drop the closing </row> tag to make the sheet XML malformed.
                    String broken = xml.replaceFirst("</row>", "");
                    if (broken.equals(xml)) {
                        // fallback: truncate the end
                        int idx = xml.lastIndexOf("</worksheet>");
                        broken = idx > 0 ? xml.substring(0, idx) : xml;
                    }
                    try (OutputStream os = part.getOutputStream()) {
                        os.write(broken.getBytes(StandardCharsets.UTF_8));
                    }
                    break;
                }
            }
        }
        return out;
    }

    private Path xxeSheetXml(Path base, String name) throws Exception {
        Path out = tempDir.resolve(name);
        Files.copy(base, out);

        String mainNs = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        String injected = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<!DOCTYPE worksheet [<!ENTITY xxe SYSTEM \"file:///nonexistent_xxe_probe\">]>\n"
                + "<worksheet xmlns=\"" + mainNs + "\">"
                + "<sheetData>&xxe;</sheetData>"
                + "</worksheet>";

        try (OPCPackage pkg = OPCPackage.open(out.toFile(), PackageAccess.READ_WRITE)) {
            for (PackagePart part : pkg.getParts()) {
                String pn = part.getPartName().getName();
                if (pn.startsWith("/xl/worksheets/sheet") && pn.endsWith(".xml")) {
                    try (OutputStream os = part.getOutputStream()) {
                        os.write(injected.getBytes(StandardCharsets.UTF_8));
                    }
                    break;
                }
            }
        }
        return out;
    }
}
