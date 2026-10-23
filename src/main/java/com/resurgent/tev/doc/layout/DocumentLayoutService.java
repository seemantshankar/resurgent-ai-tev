package com.resurgent.tev.doc.layout;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.layout.ocr.ImageRasterDecoder;
import com.resurgent.tev.doc.layout.ocr.LocalOcrClient;
import com.resurgent.tev.doc.layout.ocr.OcrGraphWriter;
import com.resurgent.tev.doc.layout.ocr.OcrScript;
import com.resurgent.tev.doc.layout.ocr.TesseractOcrClient;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * M1 layout stage: digital-text blocks/spans/tables for a document_version.
 * Replaces any previous layout for that version (idempotent parse-run style).
 */
public final class DocumentLayoutService {

    public static final String PARSER_VERSION = "0.1.0-doc-m1";
    public static final String CONFIG_HASH = "m1-digital-text";

    /** Page engine id when PDFBox supplied the text and local OCR was not called. */
    public static final String OCR_ENGINE_NONE = "none";

    private final LocalOcrClient ocrClient;

    /** Layout with the local Tesseract adapter (no network). */
    public DocumentLayoutService() {
        this(new TesseractOcrClient());
    }

    /**
     * @param ocrClient local OCR boundary; not invoked for pages with a usable
     *         digital text layer ({@value PdfLayoutExtractor#MIN_CHARS_FOR_TEXT_PAGE}
     *         or more non-whitespace characters)
     */
    public DocumentLayoutService(LocalOcrClient ocrClient) {
        this.ocrClient = Objects.requireNonNull(ocrClient, "ocrClient");
    }

    public record LayoutResult(
            long documentVersionId,
            int pageCount,
            int blockCount,
            int spanCount,
            int tableCount,
            boolean ocrRequired,
            String extractor) {}

    /**
     * Ensures a version has layout and returns its page, block, span, and table
     * counts with OCR status and an extractor identifier. Opens and migrates the
     * workspace; a version marked layout_ok with page rows is reused. Otherwise,
     * layout from matching bytes is cloned when available, sharing image paths.
     *
     * <p>When extraction is needed, existing layout and provenance rows are deleted.
     * PDFs produce text layout and selected page rasters; standalone images are
     * decoded and read by local OCR. Extraction runs in one transaction;
     * a failure rolls the layout rows back. Page image files already written
     * can remain.
     *
     * @throws IllegalArgumentException if the version does not exist
     * @throws IOException if a required original blob is missing or reading,
     *         rendering, encoding, or storing images fails
     * @throws java.sql.SQLException if workspace or layout database operations fail
     */
    public LayoutResult layout(long documentVersionId, Path dbPath, BlobStore blobStore)
            throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            DocumentRepository.DocumentVersionRow version =
                    docs.findVersion(documentVersionId).orElseThrow(
                            () -> new IllegalArgumentException(
                                    "unknown document_version " + documentVersionId));

            // A finished digital layout is reused. A raster placeholder
            // (ocr_required and no ocr_engine) is upgraded in place.
            boolean upgrade = docs.needsLocalOcrUpgrade(documentVersionId);
            if (docs.hasLayout(documentVersionId) && !upgrade) {
                return existingResult(docs, version);
            }

            Path original = blobStore.originalPath(version.fileHash(), version.fileExt());
            if (!Files.isRegularFile(original)) {
                throw new IOException("blob missing for version " + documentVersionId
                        + ": " + original);
            }

            // One transaction: delete → clone/insert → status. A mid-way failure must
            // not leave a version with no pages or partial blocks.
            var conn = db.connection();
            conn.setAutoCommit(false);
            try {
                if (!upgrade) {
                    Optional<Long> sibling = docs.findLayoutedSibling(documentVersionId,
                            version.fileHash());
                    if (sibling.isPresent() && !docs.needsLocalOcrUpgrade(sibling.get())) {
                        docs.deleteLayoutForVersion(documentVersionId);
                        docs.cloneLayout(sibling.get(), documentVersionId);
                        docs.updateVersionPageCount(documentVersionId, version.pageCount() == null
                                ? docs.countPages(documentVersionId) : version.pageCount(),
                                version.ocrRequired());
                        docs.updateVersionIngestStatus(documentVersionId, "layout_ok");
                        conn.commit();
                        return existingResult(docs, version);
                    }
                    docs.deleteLayoutForVersion(version.documentVersionId());
                }
                LayoutResult result = isPdf(version)
                        ? layoutPdf(version, original, docs, repo, blobStore)
                        : layoutImage(version, original, docs, repo, blobStore);
                conn.commit();
                return result;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // keep the original failure
                }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Counts persisted layout rows and infers the extractor identifier from block
     * count and the supplied version's OCR flag; does not read a recorded extractor.
     *
     * @throws java.sql.SQLException if querying the layout fails
     */
    private static LayoutResult existingResult(DocumentRepository docs,
            DocumentRepository.DocumentVersionRow version) throws Exception {
        int pages = docs.countPages(version.documentVersionId());
        int blocks = docs.countBlocks(version.documentVersionId());
        // spans/tables via version pages
        int spans;
        int tables;
        try (var ps = docs.connection().prepareStatement(
                "SELECT COUNT(*) FROM span s JOIN block b ON b.block_id=s.block_id"
                        + " JOIN page p ON p.page_id=b.page_id"
                        + " WHERE p.document_version_id = ?")) {
            ps.setLong(1, version.documentVersionId());
            try (var rs = ps.executeQuery()) {
                rs.next();
                spans = rs.getInt(1);
            }
        }
        try (var ps = docs.connection().prepareStatement(
                "SELECT COUNT(*) FROM table_grid tg JOIN block b ON b.block_id=tg.block_id"
                        + " JOIN page p ON p.page_id=b.page_id"
                        + " WHERE p.document_version_id = ?")) {
            ps.setLong(1, version.documentVersionId());
            try (var rs = ps.executeQuery()) {
                rs.next();
                tables = rs.getInt(1);
            }
        }
        String extractor = version.ocrRequired() && blocks == 0
                ? "pdf-scan-page-raster"
                : (blocks == 0 ? "image-placeholder" : PdfLayoutExtractor.EXTRACTOR_ID);
        return new LayoutResult(version.documentVersionId(), pages, blocks, spans, tables,
                version.ocrRequired(), extractor);
    }

    private static boolean isPdf(DocumentRepository.DocumentVersionRow version) {
        String ext = version.fileExt();
        return ext != null && ext.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private LayoutResult layoutPdf(DocumentRepository.DocumentVersionRow version, Path original,
            DocumentRepository docs, WorkspaceRepository repo, BlobStore blobStore)
            throws Exception {
        PdfLayoutExtractor extractor = new PdfLayoutExtractor();
        // Per-page rasters only where pixels matter (scan / images / no text).
        // Pure text pages keep the layout graph; UI can render those later.
        // Vision/table extractors (M2 LlamaParse) read the original PDF anyway.
        PdfLayoutExtractor.Extraction extraction = extractor.extractLayout(original);

        long versionId = version.documentVersionId();
        int blocks = 0;
        int spans = 0;
        int tables = 0;
        boolean anyPageNeedsOcr = false;

        java.util.Set<Integer> rastered = new java.util.HashSet<>();
        for (PdfLayoutExtractor.PdfPageImage image : extraction.images()) {
            blobStore.storePagePng(version.fileHash(), image.pageIndex(), image.png());
            rastered.add(image.pageIndex());
        }

        for (PageLayout page : extraction.pages()) {
            String imagePath = rastered.contains(page.pageIndex())
                    ? blobStore.pageRelativePath(version.fileHash(), page.pageIndex())
                    : null;
            long pageId = ensurePage(docs, versionId, page.pageIndex(), page.widthPx(),
                    page.heightPx(), imagePath);
            int nonWhitespace = nonWhitespace(page);
            if (nonWhitespace >= PdfLayoutExtractor.MIN_CHARS_FOR_TEXT_PAGE) {
                docs.updatePageOcr(pageId, OCR_ENGINE_NONE, null, null);
                int[] written = writeDigitalBlocks(docs, pageId, page);
                blocks += written[0];
                spans += written[1];
                tables += written[2];
            } else {
                anyPageNeedsOcr = true;
                int width = page.widthPx() == null ? 0 : page.widthPx();
                int height = page.heightPx() == null ? 0 : page.heightPx();
                Path raster = blobStore.pagePath(version.fileHash(), page.pageIndex());
                LocalOcrClient.OcrPage recognized = ocrClient.recognize(raster, width, height);
                Double confidence = recognized.words().isEmpty() ? null : recognized.confidence();
                docs.updatePageOcr(pageId, ocrClient.engineId(), confidence,
                        OcrScript.detect(recognized.words()));
                OcrGraphWriter.Counts ocrCounts = OcrGraphWriter.write(docs, pageId, recognized);
                blocks += ocrCounts.blocks();
                spans += ocrCounts.spans();
                tables += ocrCounts.tables();
            }
        }

        // A page with fewer than 40 non-whitespace characters still needs local OCR.
        // Usable digital pages do not, even if ingest flagged the file.
        boolean ocrRequired = anyPageNeedsOcr;
        String extractorId = !anyPageNeedsOcr
                ? PdfLayoutExtractor.EXTRACTOR_ID
                : (extraction.anyText() ? PdfLayoutExtractor.EXTRACTOR_ID : OcrGraphWriter.EXTRACTOR_ID);
        docs.updateVersionPageCount(versionId, extraction.pageCount(), ocrRequired);
        docs.updateVersionIngestStatus(versionId, "layout_ok");
        recordLayoutRun(docs, repo, version, extraction.pageCount(), blocks, spans, tables,
                extractorId);

        return new LayoutResult(versionId, extraction.pageCount(), blocks, spans, tables,
                ocrRequired, extractorId);
    }

    private static int[] writeDigitalBlocks(DocumentRepository docs, long pageId, PageLayout page)
            throws Exception {
        int blocks = 0;
        int spans = 0;
        int tables = 0;
        int blockIndex = 0;
        for (LayoutBlock block : page.blocks()) {
            long blockId = docs.insertBlock(pageId, blockIndex, block.blockKind(),
                    block.bbox().x0(), block.bbox().y0(), block.bbox().x1(), block.bbox().y1(),
                    block.textRaw(), block.textNorm(), blockIndex,
                    PdfLayoutExtractor.EXTRACTOR_ID);
            blocks++;
            int spanIndex = 0;
            for (LayoutLine line : block.lines()) {
                for (LayoutSpan span : line.spans()) {
                    docs.insertSpan(blockId, spanIndex, span.text(),
                            span.bbox().x0(), span.bbox().y0(),
                            span.bbox().x1(), span.bbox().y1(), 1.0);
                    spanIndex++;
                    spans++;
                }
            }
            if (block.isTable()) {
                tables += writeTable(docs, blockId, block);
            }
            blockIndex++;
        }
        return new int[] {blocks, spans, tables};
    }

    /**
     * Reuses a page row when one already exists for this index (placeholder
     * upgrade) and otherwise inserts a new row.
     */
    private static long ensurePage(DocumentRepository docs, long versionId, int pageIndex,
            Integer widthPx, Integer heightPx, String imagePath) throws SQLException {
        Optional<Long> existing = docs.findPageId(versionId, pageIndex);
        if (existing.isPresent()) {
            long pageId = existing.get();
            docs.clearPageGraph(pageId);
            docs.updatePageRaster(pageId, widthPx, heightPx, imagePath);
            return pageId;
        }
        return docs.insertPage(versionId, pageIndex, widthPx, heightPx, imagePath);
    }

    /** Characters that are not Unicode whitespace, across every block on the page. */
    static int nonWhitespace(PageLayout page) {
        int count = 0;
        for (LayoutBlock block : page.blocks()) {
            String text = block.textRaw();
            if (text == null) {
                continue;
            }
            for (int i = 0; i < text.length();) {
                int cp = text.codePointAt(i);
                if (!Character.isWhitespace(cp)) {
                    count++;
                }
                i += Character.charCount(cp);
            }
        }
        return count;
    }

    /**
     * Writes one table grid, treating the first row as a header. Cell boxes divide
     * each row box evenly by the maximum column count, falling back to the block box
     * when a row box is absent.
     *
     * @return one, the number of grids inserted
     * @throws java.sql.SQLException if inserting the grid or cells fails
     */
    private static int writeTable(DocumentRepository docs, long blockId, LayoutBlock block)
            throws Exception {
        List<List<String>> rows = block.tableRows();
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        long gridId = docs.insertTableGrid(blockId, rows.size(), cols, rows.isEmpty() ? 0 : 1);
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            BBox rowBox = r < block.tableRowBoxes().size()
                    ? block.tableRowBoxes().get(r)
                    : block.bbox();
            for (int c = 0; c < cells.size(); c++) {
                double colWidth = cols == 0 ? 0 : rowBox.width() / cols;
                double x0 = rowBox.x0() + c * colWidth;
                docs.insertTableCell(gridId, r, c, cells.get(c),
                        x0, rowBox.y0(), x0 + colWidth, rowBox.y1(), r == 0);
            }
        }
        return 1;
    }

    /**
     * Creates or updates the shared layout run for the source file. Does nothing
     * when the version has no source file.
     *
     * @throws java.sql.SQLException if finding the mandate or persisting the run fails
     */
    private static void recordLayoutRun(DocumentRepository docs, WorkspaceRepository repo,
            DocumentRepository.DocumentVersionRow version, int pages, int blocks, int spans,
            int tables, String extractor) throws Exception {
        Long sourceFileId = version.sourceFileId();
        if (sourceFileId == null) {
            return;
        }
        long mandateId = docs.findMandateId(version.documentId());
        String now = Timestamps.now();
        String metrics = "{\"stage\":\"layout\",\"pages\":" + pages
                + ",\"blocks\":" + blocks + ",\"spans\":" + spans + ",\"tables\":" + tables
                + ",\"extractor\":\"" + extractor + "\"}";
        Long existing = repo.findParseRunId(sourceFileId, PARSER_VERSION, CONFIG_HASH);
        if (existing != null) {
            repo.updateParseRunResult(existing, now, "success", metrics);
        } else {
            repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION, CONFIG_HASH,
                    now, now, "success", metrics);
        }
    }

    /**
     * Decodes a standalone screenshot, normalizes each frame to a page PNG, and
     * runs local OCR. Pixel dimensions are the decoded frame size.
     */
    private LayoutResult layoutImage(DocumentRepository.DocumentVersionRow version,
            Path original, DocumentRepository docs, WorkspaceRepository repo, BlobStore blobStore)
            throws Exception {
        List<ImageRasterDecoder.Frame> frames = ImageRasterDecoder.decode(original);
        long versionId = version.documentVersionId();
        int blocks = 0;
        int spans = 0;
        int tables = 0;
        int pageIndex = 1;
        for (ImageRasterDecoder.Frame frame : frames) {
            blobStore.storePagePng(version.fileHash(), pageIndex, encodePng(frame.image()));
            String rel = blobStore.pageRelativePath(version.fileHash(), pageIndex);
            long pageId = ensurePage(docs, versionId, pageIndex, frame.widthPx(), frame.heightPx(), rel);
            LocalOcrClient.OcrPage recognized = ocrClient.recognize(
                    blobStore.pagePath(version.fileHash(), pageIndex),
                    frame.widthPx(), frame.heightPx());
            Double confidence = recognized.words().isEmpty() ? null : recognized.confidence();
            docs.updatePageOcr(pageId, ocrClient.engineId(), confidence,
                    OcrScript.detect(recognized.words()));
            OcrGraphWriter.Counts counts = OcrGraphWriter.write(docs, pageId, recognized);
            blocks += counts.blocks();
            spans += counts.spans();
            tables += counts.tables();
            pageIndex++;
        }
        int pageCount = frames.size();
        docs.updateVersionPageCount(versionId, pageCount, true);
        docs.updateVersionIngestStatus(versionId, "layout_ok");
        recordLayoutRun(docs, repo, version, pageCount, blocks, spans, tables,
                OcrGraphWriter.EXTRACTOR_ID);
        return new LayoutResult(versionId, pageCount, blocks, spans, tables, true,
                OcrGraphWriter.EXTRACTOR_ID);
    }

    private static byte[] encodePng(java.awt.image.BufferedImage image) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(image, "PNG", out)) {
            throw new java.io.IOException("PNG writer unavailable");
        }
        return out.toByteArray();
    }
}
