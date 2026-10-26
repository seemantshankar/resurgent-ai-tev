package com.resurgent.tev.doc.layout;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.ocr.LocalOcrClient;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hydrates layout blocks from {@code cloud_parse_page} when local OCR is empty. */
class CloudParseLayoutHydratorTest {

    @TempDir
    Path tempDir;

    @Test
    void layoutHydratesFromCloudParseWhenLocalOcrReturnsNothing() throws Exception {
        Path pdf = tempDir.resolve("scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            doc.save(pdf.toFile());
        }
        Path db = tempDir.resolve("workspace.db");
        BlobStore blobs = BlobStore.besideDatabase(db);
        DocumentIngestService.IngestResult ingested = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, 1L, "scan", "v1", "scan.pdf", "technical_report"),
                db, blobs);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            new CloudParsePageRepository(workspace.connection()).writeAll(
                    ingested.documentVersionId(),
                    java.util.List.of(new CloudParsePageRepository.StoredPage(
                            1, "pjb-test", "agentic", "# Heading\nRevenue: 100", "Revenue: 100")));
        }

        LocalOcrClient emptyOcr = (image, widthPx, heightPx) -> LocalOcrClient.OcrPage.empty();
        DocumentLayoutService.LayoutResult layout = new DocumentLayoutService(emptyOcr).layout(
                ingested.documentVersionId(), db, blobs);

        assertThat(layout.blockCount()).isGreaterThan(0);
        assertThat(layout.spanCount()).isGreaterThan(0);
        assertThat(layout.extractor()).contains(CloudParseLayoutHydrator.EXTRACTOR_ID);
    }
}
