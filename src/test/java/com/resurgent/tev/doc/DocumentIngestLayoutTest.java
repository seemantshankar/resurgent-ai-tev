package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M1 end-to-end: synthetic born-digital PDF → ingest → layout → SQLite layout graph.
 */
class DocumentIngestLayoutTest {

    @TempDir
    Path tempDir;

    private Path dbPath() {
        return tempDir.resolve("workspace.db");
    }

    private Path writeSamplePdf() throws Exception {
        Path pdf = tempDir.resolve("PPA_v1.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(bold, 16);
                cs.newLineAtOffset(72, 720);
                cs.showText("Power Purchase Agreement");
                cs.endText();

                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(72, 680);
                cs.showText("This Agreement is made between the Seller and the Buyer.");
                cs.endText();
                cs.beginText();
                cs.newLineAtOffset(72, 662);
                cs.showText("Tariff shall be Rs. 4.50 per kWh for a tenure of 25 years.");
                cs.endText();

                // Simple aligned table
                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(72, 600);
                cs.showText("Item");
                cs.endText();
                cs.beginText();
                cs.newLineAtOffset(220, 600);
                cs.showText("Value");
                cs.endText();
                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(72, 580);
                cs.showText("Capacity");
                cs.endText();
                cs.beginText();
                cs.newLineAtOffset(220, 580);
                cs.showText("10 MW");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static List<String> tableNames(java.sql.Connection c) throws Exception {
        try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
            return names;
        }
    }

    private static long count(java.sql.Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void migrationsCreateDocumentLayoutTables() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath())) {
            List<String> tables = tableNames(db.connection());
            assertThat(tables).contains(
                    "document", "document_version", "document_relation",
                    "page", "block", "span", "table_grid", "table_cell", "provenance_doc");
        }
    }

    @Test
    void ingestThenLayoutProducesLayoutGraphAndIsIdempotentOnReIngest() throws Exception {
        Path pdf = writeSamplePdf();
        Path db = dbPath();
        BlobStore blobs = BlobStore.besideDatabase(db);

        DocumentIngestService.IngestResult first = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, 1L, "PPA", "v1", "agreement", "agreement"),
                db, blobs);
        assertThat(first.reused()).isFalse();
        assertThat(first.pageCount()).isEqualTo(1);
        assertThat(first.ocrRequired()).isFalse();
        assertThat(Files.isRegularFile(first.blobPath())).isTrue();

        DocumentIngestService.IngestResult second = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, 1L, "PPA", "v1", "agreement", "agreement"),
                db, blobs);
        assertThat(second.reused()).isTrue();
        assertThat(second.documentVersionId()).isEqualTo(first.documentVersionId());

        DocumentLayoutService.LayoutResult layout = new DocumentLayoutService().layout(
                first.documentVersionId(), db, blobs);
        assertThat(layout.pageCount()).isEqualTo(1);
        assertThat(layout.blockCount()).isGreaterThanOrEqualTo(2);
        assertThat(layout.spanCount()).isGreaterThan(0);
        assertThat(layout.extractor()).isEqualTo("pdfbox-digital-text");

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            DocumentRepository docs = new DocumentRepository(workspace.connection());
            assertThat(docs.countBlocks(first.documentVersionId()))
                    .isEqualTo(layout.blockCount());
            assertThat(count(workspace.connection(),
                    "SELECT COUNT(*) FROM span")).isEqualTo(layout.spanCount());
            assertThat(count(workspace.connection(),
                    "SELECT COUNT(*) FROM page WHERE document_version_id = "
                            + first.documentVersionId())).isEqualTo(1);
            // Re-layout replaces, does not duplicate.
            new DocumentLayoutService().layout(first.documentVersionId(), db, blobs);
            assertThat(docs.countBlocks(first.documentVersionId()))
                    .isEqualTo(layout.blockCount());
        }
    }

    @Test
    void imageIngestMarksOcrRequired() throws Exception {
        Path png = tempDir.resolve("scan.png");
        var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(image, "PNG", png.toFile());
        BlobStore blobs = BlobStore.besideDatabase(dbPath());
        DocumentIngestService.IngestResult result = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        png, 1L, "Scan", "v1", "certificate", "certificate"),
                dbPath(), blobs);
        assertThat(result.ocrRequired()).isTrue();
        assertThat(result.pageCount()).isEqualTo(1);
        DocumentLayoutService.LayoutResult layout = new DocumentLayoutService().layout(
                result.documentVersionId(), dbPath(), blobs);
        assertThat(layout.ocrRequired()).isTrue();
        assertThat(layout.extractor()).isEqualTo("image-placeholder");
    }
}
