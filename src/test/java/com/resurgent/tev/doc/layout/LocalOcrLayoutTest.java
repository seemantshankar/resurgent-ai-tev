package com.resurgent.tev.doc.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.ocr.LocalOcrClient;
import com.resurgent.tev.doc.layout.ocr.MarkEvidenceLinker;
import com.resurgent.tev.doc.layout.ocr.OcrLanguagePacks;
import com.resurgent.tev.doc.layout.ocr.TesseractOcrClient;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Local-first OCR during {@code tev-doc layout} (issue #172).
 * Synthetic pages only — no {@code fixtures/} and no network.
 */
class LocalOcrLayoutTest {

    @TempDir
    Path tempDir;

    @Test
    void bornDigitalPageUsesPdfBoxAndDoesNotCallLocalOcr() throws Exception {
        Path pdf = tempDir.resolve("agreement.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("This Power Purchase Agreement is made between the Seller and the Buyer.");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }

        Path db = tempDir.resolve("workspace.db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, 1L, "PPA", "v1", "agreement", "agreement"),
                db, blobs);

        LocalOcrClient ocr = (image, widthPx, heightPx) -> {
            throw new AssertionError("born-digital page must not be sent to local OCR");
        };
        DocumentLayoutService.LayoutResult layout = new DocumentLayoutService(ocr).layout(
                ingested.documentVersionId(), db, blobs);

        assertThat(layout.spanCount()).isGreaterThan(0);
        assertThat(layout.ocrRequired()).isFalse();
        assertThat(layout.extractor()).isEqualTo("pdfbox-digital-text");

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            DocumentRepository docs = new DocumentRepository(workspace.connection());
            assertThat(docs.countPages(ingested.documentVersionId())).isEqualTo(1);
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT p.ocr_engine, v.ocr_required, s.text_raw"
                            + " FROM page p"
                            + " JOIN document_version v ON v.document_version_id = p.document_version_id"
                            + " JOIN block b ON b.page_id = p.page_id"
                            + " JOIN span s ON s.block_id = b.block_id"
                            + " WHERE p.document_version_id = " + ingested.documentVersionId()
                            + " ORDER BY s.span_id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("ocr_engine")).isEqualTo("none");
                assertThat(rs.getInt("ocr_required")).isEqualTo(0);
                StringBuilder text = new StringBuilder(rs.getString("text_raw"));
                while (rs.next()) {
                    text.append(' ').append(rs.getString("text_raw"));
                }
                assertThat(text.toString()).contains("Power Purchase Agreement");
            }
        }
    }

    @Test
    void scannedPageIsRecognizedAt300DpiWithIndicWordsBoxesAndConfidence() throws Exception {
        Path pdf = tempDir.resolve("hybrid.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage digital = new PDPage(PDRectangle.LETTER);
            doc.addPage(digital);
            try (PDPageContentStream cs = new PDPageContentStream(doc, digital)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("This Power Purchase Agreement is made between the Seller and the Buyer.");
                cs.endText();
            }
            doc.addPage(new PDPage(PDRectangle.LETTER));
            doc.save(pdf.toFile());
        }

        Path db = tempDir.resolve("workspace.db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, 1L, "Hybrid", "v1", "agreement", "agreement"),
                db, blobs);

        LocalOcrClient ocr = (image, widthPx, heightPx) -> new LocalOcrClient.OcrPage(List.of(
                new LocalOcrClient.OcrWord("बकाया", new BBox(10, 20, 80, 44), 0.90),
                new LocalOcrClient.OcrWord("राशि", new BBox(90, 20, 150, 44), 0.80)));

        new DocumentLayoutService(ocr).layout(ingested.documentVersionId(), db, blobs);

        assertThat(blobs.pagePath(ingested.fileHash(), 2)).exists();
        assertThat(blobs.pagePath(ingested.fileHash(), 1)).doesNotExist();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT page_index, ocr_engine, ocr_confidence, lang_detected, width_px, height_px"
                            + " FROM page WHERE document_version_id = "
                            + ingested.documentVersionId()
                            + " ORDER BY page_index")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("page_index")).isEqualTo(1);
                assertThat(rs.getString("ocr_engine")).isEqualTo("none");

                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("page_index")).isEqualTo(2);
                assertThat(rs.getString("ocr_engine")).isEqualTo("fixture");
                assertThat(rs.getDouble("ocr_confidence")).isCloseTo(0.85, within(1e-9));
                assertThat(rs.getString("lang_detected")).isEqualTo("hin");
                // Letter at 300 DPI is 2550×3300. PDFBox's float scale truncates the height by one pixel.
                assertThat(rs.getInt("width_px")).isEqualTo(2550);
                assertThat(rs.getInt("height_px")).isBetween(3299, 3300);
                assertThat(rs.next()).isFalse();
            }
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT s.text_raw, s.confidence, s.bbox_x0, s.bbox_y0, s.bbox_x1, s.bbox_y1"
                            + " FROM span s"
                            + " JOIN block b ON b.block_id = s.block_id"
                            + " JOIN page p ON p.page_id = b.page_id"
                            + " WHERE p.document_version_id = " + ingested.documentVersionId()
                            + " AND p.page_index = 2"
                            + " ORDER BY s.span_index")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("text_raw")).isEqualTo("बकाया");
                assertThat(rs.getDouble("confidence")).isEqualTo(0.90);
                assertThat(rs.getDouble("bbox_x0")).isEqualTo(10);
                assertThat(rs.getDouble("bbox_y0")).isEqualTo(20);
                assertThat(rs.getDouble("bbox_x1")).isEqualTo(80);
                assertThat(rs.getDouble("bbox_y1")).isEqualTo(44);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("text_raw")).isEqualTo("राशि");
                assertThat(rs.getDouble("confidence")).isEqualTo(0.80);
                assertThat(rs.getDouble("bbox_x0")).isEqualTo(90);
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void standalonePngIsDecodedAndRecognizedInsteadOfAPlaceholder() throws Exception {
        Path png = tempDir.resolve("cibil.png");
        ImageIO.write(new BufferedImage(64, 40, BufferedImage.TYPE_INT_RGB), "PNG", png.toFile());

        Path db = tempDir.resolve("workspace.db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        png, 1L, "CIBIL", "v1", "certificate", "certificate"),
                db, blobs);
        LocalOcrClient ocr = (image, widthPx, heightPx) -> new LocalOcrClient.OcrPage(List.of(
                new LocalOcrClient.OcrWord("CIBIL", new BBox(4, 6, 40, 18), 0.91)));

        DocumentLayoutService.LayoutResult layout = new DocumentLayoutService(ocr).layout(
                ingested.documentVersionId(), db, blobs);

        assertThat(layout.extractor()).isEqualTo("local-ocr");
        assertThat(layout.blockCount()).isEqualTo(1);
        assertThat(layout.ocrRequired()).isTrue();
        assertThat(blobs.pagePath(ingested.fileHash(), 1)).exists();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT width_px, height_px, ocr_engine, ocr_confidence FROM page"
                            + " WHERE document_version_id = " + ingested.documentVersionId())) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("width_px")).isEqualTo(64);
                assertThat(rs.getInt("height_px")).isEqualTo(40);
                assertThat(rs.getString("ocr_engine")).isEqualTo("fixture");
                assertThat(rs.getDouble("ocr_confidence")).isEqualTo(0.91);
                assertThat(rs.next()).isFalse();
            }
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT text_raw FROM span")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("CIBIL");
            }
        }
    }

    @Test
    void jpegWebpAndMultiFrameTiffKeepRealPixelDimensions() throws Exception {
        Path jpeg = tempDir.resolve("portal.jpg");
        ImageIO.write(new BufferedImage(48, 22, BufferedImage.TYPE_INT_RGB), "jpg", jpeg.toFile());
        assertScreenshot(jpeg, "Portal JPG", List.of(new int[] {48, 22}));

        Path webp = tempDir.resolve("portal.webp");
        Files.write(webp, new byte[] {
                (byte) 0x52, (byte) 0x49, (byte) 0x46, (byte) 0x46, (byte) 0x30, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x57, (byte) 0x45, (byte) 0x42, (byte) 0x50,
                (byte) 0x56, (byte) 0x50, (byte) 0x38, (byte) 0x20, (byte) 0x24, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x50, (byte) 0x01, (byte) 0x00, (byte) 0x9D,
                (byte) 0x01, (byte) 0x2A, (byte) 0x10, (byte) 0x00, (byte) 0x09, (byte) 0x00,
                (byte) 0x01, (byte) 0x40, (byte) 0x26, (byte) 0x25, (byte) 0x00, (byte) 0x4E,
                (byte) 0x80, (byte) 0x28, (byte) 0x00, (byte) 0x00, (byte) 0xFE, (byte) 0xFA,
                (byte) 0x89, (byte) 0xDF, (byte) 0x91, (byte) 0xD8, (byte) 0x43, (byte) 0x76,
                (byte) 0x1C, (byte) 0xBA, (byte) 0xE2, (byte) 0xE6, (byte) 0xC8, (byte) 0x6F,
                (byte) 0x00, (byte) 0x00
        });
        assertScreenshot(webp, "Portal WEBP", List.of(new int[] {16, 9}));

        Path tiff = tempDir.resolve("schedule.tiff");
        writeTiff(tiff,
                new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB),
                new BufferedImage(30, 12, BufferedImage.TYPE_INT_RGB));
        assertScreenshot(tiff, "Schedule TIFF", List.of(new int[] {20, 10}, new int[] {30, 12}));
    }

    private void assertScreenshot(Path file, String logicalName, List<int[]> frames) throws Exception {
        Path db = tempDir.resolve(logicalName.replace(' ', '-') + ".db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        file, 1L, logicalName, "v1", "certificate", "certificate"),
                db, blobs);
        LocalOcrClient ocr = (image, widthPx, heightPx) -> new LocalOcrClient.OcrPage(List.of(
                new LocalOcrClient.OcrWord("p" + widthPx + "x" + heightPx,
                        new BBox(0, 0, 1, 1), 0.7)));
        DocumentLayoutService.LayoutResult layout = new DocumentLayoutService(ocr).layout(
                ingested.documentVersionId(), db, blobs);
        assertThat(layout.pageCount()).isEqualTo(frames.size());
        assertThat(layout.blockCount()).isEqualTo(frames.size());
        assertThat(layout.extractor()).isEqualTo("local-ocr");

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT page_index, width_px, height_px FROM page"
                            + " WHERE document_version_id = " + ingested.documentVersionId()
                            + " ORDER BY page_index")) {
                for (int i = 0; i < frames.size(); i++) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("width_px")).isEqualTo(frames.get(i)[0]);
                    assertThat(rs.getInt("height_px")).isEqualTo(frames.get(i)[1]);
                }
                assertThat(rs.next()).isFalse();
            }
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT p.page_index, s.text_raw FROM span s"
                            + " JOIN block b ON b.block_id = s.block_id"
                            + " JOIN page p ON p.page_id = b.page_id"
                            + " ORDER BY p.page_index")) {
                for (int[] frame : frames) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("text_raw"))
                            .isEqualTo("p" + frame[0] + "x" + frame[1]);
                }
            }
            byte[] png = Files.readAllBytes(blobs.pagePath(ingested.fileHash(), 1));
            assertThat(png[0]).isEqualTo((byte) 0x89);
            assertThat(png[1]).isEqualTo((byte) 0x50);
            assertThat(png[2]).isEqualTo((byte) 0x4E);
            assertThat(png[3]).isEqualTo((byte) 0x47);
        }
    }

    private static void writeTiff(Path file, BufferedImage... frames) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (BufferedImage frame : frames) {
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    @Test
    void alignedWordBoxesBecomeCoordinateAlignedTableCells() throws Exception {
        Path png = tempDir.resolve("schedule.png");
        ImageIO.write(new BufferedImage(320, 80, BufferedImage.TYPE_INT_RGB), "PNG", png.toFile());
        Path db = tempDir.resolve("schedule.db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        png, 1L, "Schedule", "v1", "certificate", "certificate"),
                db, blobs);
        LocalOcrClient ocr = (image, widthPx, heightPx) -> new LocalOcrClient.OcrPage(List.of(
                new LocalOcrClient.OcrWord("Account", new BBox(10, 10, 90, 28), 0.95),
                new LocalOcrClient.OcrWord("Balance", new BBox(200, 10, 290, 28), 0.94),
                new LocalOcrClient.OcrWord("Overdue", new BBox(10, 40, 90, 58), 0.93),
                new LocalOcrClient.OcrWord("12000", new BBox(200, 40, 270, 58), 0.92)));
        new DocumentLayoutService(ocr).layout(ingested.documentVersionId(), db, blobs);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT tg.n_rows, tg.n_cols, tg.header_row_count, b.block_kind"
                            + " FROM table_grid tg JOIN block b ON b.block_id = tg.block_id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("n_rows")).isEqualTo(2);
                assertThat(rs.getInt("n_cols")).isEqualTo(2);
                assertThat(rs.getInt("header_row_count")).isEqualTo(1);
                assertThat(rs.getString("block_kind")).isEqualTo("table");
                assertThat(rs.next()).isFalse();
            }
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT row_index, col_index, text_raw, bbox_x0, bbox_y0, bbox_x1, bbox_y1,"
                            + " is_header FROM table_cell ORDER BY row_index, col_index")) {
                assertCell(rs, 0, 0, "Account", 10, 10, 90, 28, true);
                assertCell(rs, 0, 1, "Balance", 200, 10, 290, 28, true);
                assertCell(rs, 1, 0, "Overdue", 10, 40, 90, 58, false);
                assertCell(rs, 1, 1, "12000", 200, 40, 270, 58, false);
                assertThat(rs.next()).isFalse();
            }
        }
    }

    private static void assertCell(java.sql.ResultSet rs, int row, int col, String text,
            double x0, double y0, double x1, double y1, boolean header) throws Exception {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("row_index")).isEqualTo(row);
        assertThat(rs.getInt("col_index")).isEqualTo(col);
        assertThat(rs.getString("text_raw")).isEqualTo(text);
        assertThat(rs.getDouble("bbox_x0")).isEqualTo(x0);
        assertThat(rs.getDouble("bbox_y0")).isEqualTo(y0);
        assertThat(rs.getDouble("bbox_x1")).isEqualTo(x1);
        assertThat(rs.getDouble("bbox_y1")).isEqualTo(y1);
        assertThat(rs.getInt("is_header")).isEqualTo(header ? 1 : 0);
    }

    @Test
    void stampSignatureAndHandwritingAreTaggedAndLinkedToFacts() throws Exception {
        Path png = tempDir.resolve("attested.png");
        ImageIO.write(new BufferedImage(420, 120, BufferedImage.TYPE_INT_RGB), "PNG", png.toFile());
        Path db = tempDir.resolve("attested.db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        png, 1L, "Attested", "v1", "certificate", "certificate"),
                db, blobs);
        LocalOcrClient ocr = (image, widthPx, heightPx) -> new LocalOcrClient.OcrPage(List.of(
                new LocalOcrClient.OcrWord("Dues", new BBox(200, 40, 250, 58), 0.96),
                new LocalOcrClient.OcrWord("OFFICIAL", new BBox(100, 70, 180, 88), 0.90),
                new LocalOcrClient.OcrWord("STAMP", new BBox(190, 70, 260, 88), 0.88),
                new LocalOcrClient.OcrWord("Authorised", new BBox(190, 8, 280, 22), 0.86),
                new LocalOcrClient.OcrWord("Signatory", new BBox(285, 8, 400, 22), 0.84),
                new LocalOcrClient.OcrWord("Received", new BBox(205, 44, 250, 56), 0.55, true)));
        new DocumentLayoutService(ocr).layout(ingested.documentVersionId(), db, blobs);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            var connection = workspace.connection();
            try (var rs = connection.createStatement().executeQuery(
                    "SELECT block_kind, is_handwritten, text_raw FROM block"
                            + " WHERE block_kind IN ('stamp', 'signature', 'handwriting')"
                            + " ORDER BY block_kind")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("block_kind")).isEqualTo("handwriting");
                assertThat(rs.getInt("is_handwritten")).isEqualTo(1);
                assertThat(rs.getString("text_raw")).isEqualTo("Received");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("block_kind")).isEqualTo("signature");
                assertThat(rs.getInt("is_handwritten")).isEqualTo(0);
                assertThat(rs.getString("text_raw")).isEqualTo("Authorised Signatory");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("block_kind")).isEqualTo("stamp");
                assertThat(rs.getString("text_raw")).isEqualTo("OFFICIAL STAMP");
                assertThat(rs.next()).isFalse();
            }

            long pageId;
            try (var rs = connection.createStatement().executeQuery(
                    "SELECT page_id FROM page WHERE document_version_id = "
                            + ingested.documentVersionId())) {
                assertThat(rs.next()).isTrue();
                pageId = rs.getLong(1);
            }
            long factId;
            try (var ps = connection.prepareStatement(
                    "INSERT INTO fact (parse_run_id, document_version_id, field_key, value_kind,"
                            + " value_text, extraction_method, created_at)"
                            + " VALUES (?, ?, 'dues', 'text', '12000', 'deterministic', ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, ingested.parseRunId());
                ps.setLong(2, ingested.documentVersionId());
                ps.setString(3, "2026-10-23T00:00:00Z");
                ps.executeUpdate();
                try (var keys = ps.getGeneratedKeys()) {
                    keys.next();
                    factId = keys.getLong(1);
                }
            }
            try (var ps = connection.prepareStatement(
                    "INSERT INTO provenance_doc (entity_type, entity_id, document_version_id,"
                            + " page_id, bbox_x0, bbox_y0, bbox_x1, bbox_y1, verbatim, is_derived)"
                            + " VALUES ('fact', ?, ?, ?, 200, 40, 270, 58, '12000', 0)")) {
                ps.setLong(1, factId);
                ps.setLong(2, ingested.documentVersionId());
                ps.setLong(3, pageId);
                ps.executeUpdate();
            }
            MarkEvidenceLinker.attach(connection, ingested.documentVersionId());
            MarkEvidenceLinker.attach(connection, ingested.documentVersionId());

            try (var rs = connection.createStatement().executeQuery(
                    "SELECT role, source_text, resolution FROM fact_evidence"
                            + " WHERE fact_id = " + factId + " ORDER BY role")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("role")).isEqualTo("handwriting");
                assertThat(rs.getString("source_text")).isEqualTo("Received");
                assertThat(rs.getString("resolution")).isEqualTo("resolved");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("role")).isEqualTo("signature_block");
                assertThat(rs.getString("source_text")).isEqualTo("Authorised Signatory");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("role")).isEqualTo("stamp");
                assertThat(rs.getString("source_text")).isEqualTo("OFFICIAL STAMP");
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void relayoutUpgradesARasterPlaceholderInPlace() throws Exception {
        Path png = tempDir.resolve("old-scan.png");
        ImageIO.write(new BufferedImage(50, 20, BufferedImage.TYPE_INT_RGB), "PNG", png.toFile());
        Path db = tempDir.resolve("upgrade.db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        png, 1L, "Old scan", "v1", "certificate", "certificate"),
                db, blobs);
        long pageId;
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            try (var st = workspace.connection().createStatement()) {
                st.executeUpdate("UPDATE document_version SET ingest_status = 'layout_ok',"
                        + " ocr_required = 1 WHERE document_version_id = "
                        + ingested.documentVersionId());
                try (var rs = st.executeQuery(
                        "SELECT page_id, ocr_engine FROM page WHERE document_version_id = "
                                + ingested.documentVersionId())) {
                    assertThat(rs.next()).isTrue();
                    pageId = rs.getLong("page_id");
                    assertThat(rs.getString("ocr_engine")).isNull();
                }
            }
        }

        LocalOcrClient ocr = (image, widthPx, heightPx) -> new LocalOcrClient.OcrPage(List.of(
                new LocalOcrClient.OcrWord("Upgraded", new BBox(2, 2, 40, 16), 0.77)));
        DocumentLayoutService service = new DocumentLayoutService(ocr);
        DocumentLayoutService.LayoutResult first = service.layout(
                ingested.documentVersionId(), db, blobs);
        assertThat(first.blockCount()).isEqualTo(1);
        assertThat(first.pageCount()).isEqualTo(1);

        DocumentLayoutService.LayoutResult second = service.layout(
                ingested.documentVersionId(), db, blobs);
        assertThat(second.blockCount()).isEqualTo(1);
        assertThat(second.pageCount()).isEqualTo(1);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT page_id, ocr_engine, width_px, height_px FROM page"
                            + " WHERE document_version_id = " + ingested.documentVersionId())) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("page_id")).isEqualTo(pageId);
                assertThat(rs.getString("ocr_engine")).isEqualTo("fixture");
                assertThat(rs.getInt("width_px")).isEqualTo(50);
                assertThat(rs.getInt("height_px")).isEqualTo(20);
                assertThat(rs.next()).isFalse();
            }
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT COUNT(*) FROM block b JOIN page p ON p.page_id = b.page_id"
                            + " WHERE p.document_version_id = " + ingested.documentVersionId())) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT text_raw FROM span")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Upgraded");
                assertThat(rs.next()).isFalse();
            }
        }

        Path scan = tempDir.resolve("old-scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            doc.save(scan.toFile());
        }
        Path scanDb = tempDir.resolve("scan-upgrade.db");
        BlobStore scanBlobs = BlobStore.besideDatabase(scanDb);
        DocumentIngestService.IngestResult scanIngest = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        scan, 1L, "Old scan pdf", "v1", "agreement", "agreement"),
                scanDb, scanBlobs);
        long scanPageId;
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(scanDb)) {
            try (var st = workspace.connection().createStatement()) {
                st.executeUpdate("UPDATE document_version SET ingest_status = 'layout_ok',"
                        + " ocr_required = 1 WHERE document_version_id = "
                        + scanIngest.documentVersionId());
                try (var rs = st.executeQuery(
                        "SELECT page_id FROM page WHERE document_version_id = "
                                + scanIngest.documentVersionId())) {
                    assertThat(rs.next()).isTrue();
                    scanPageId = rs.getLong(1);
                    assertThat(rs.next()).isFalse();
                }
            }
        }
        service.layout(scanIngest.documentVersionId(), scanDb, scanBlobs);
        service.layout(scanIngest.documentVersionId(), scanDb, scanBlobs);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(scanDb)) {
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT page_id, ocr_engine FROM page WHERE document_version_id = "
                            + scanIngest.documentVersionId())) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("page_id")).isEqualTo(scanPageId);
                assertThat(rs.getString("ocr_engine")).isEqualTo("fixture");
                assertThat(rs.next()).isFalse();
            }
            try (var rs = workspace.connection().createStatement().executeQuery(
                    "SELECT COUNT(*) FROM block")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void languageProbeSelectsInstalledIndicPacksOrTheEnvOverride() {
        assertThat(OcrLanguagePacks.DEFAULT_INDIC).containsExactly(
                "hin", "mar", "guj", "ben", "tam", "tel", "kan", "mal", "pan", "ori");
        assertThat(OcrLanguagePacks.select(null, code -> false)).containsExactly("eng");
        assertThat(OcrLanguagePacks.select(null,
                code -> "hin".equals(code) || "tam".equals(code)))
                .containsExactly("eng", "hin", "tam");
        assertThat(OcrLanguagePacks.select("mar+guj", code -> {
            throw new AssertionError("TEV_OCR_LANGS must not probe installed packs");
        })).containsExactly("eng", "mar", "guj");
    }

    @Test
    void tesseractTsvReadsWordBoxesWithoutABinary() {
        String tsv = """
                level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
                5\t1\t1\t1\t1\t1\t10\t20\t70\t24\t90.00\tबकाया
                4\t1\t1\t1\t1\t0\t10\t20\t140\t24\t-1\t
                5\t1\t1\t1\t1\t2\t90\t20\t60\t24\t80.00\tराशि
                """;
        LocalOcrClient.OcrPage page = TesseractOcrClient.parseTsv(tsv);
        assertThat(page.words()).hasSize(2);
        assertThat(page.words().get(0).text()).isEqualTo("बकाया");
        assertThat(page.words().get(0).bbox()).isEqualTo(new BBox(10, 20, 80, 44));
        assertThat(page.words().get(0).confidence()).isEqualTo(0.9);
        assertThat(page.words().get(1).text()).isEqualTo("राशि");
        assertThat(page.words().get(1).confidence()).isEqualTo(0.8);
        assertThat(page.words().get(0).handwritten()).isFalse();
    }
}
