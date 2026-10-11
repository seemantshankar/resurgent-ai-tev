package com.resurgent.tev.doc.layout;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentFileKind;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * M1 layout stage: digital-text blocks/spans/tables for a document_version.
 * Replaces any previous layout for that version (idempotent parse-run style).
 */
public final class DocumentLayoutService {

    public static final String PARSER_VERSION = "0.1.0-doc-m1";
    public static final String CONFIG_HASH = "m1-digital-text";

    public record LayoutResult(
            long documentVersionId,
            int pageCount,
            int blockCount,
            int spanCount,
            int tableCount,
            boolean ocrRequired,
            String extractor) {}

    public LayoutResult layout(long documentVersionId, Path dbPath, BlobStore blobStore)
            throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            DocumentRepository.DocumentVersionRow version =
                    docs.findVersion(documentVersionId).orElseThrow(
                            () -> new IllegalArgumentException(
                                    "unknown document_version " + documentVersionId));

            Path original = blobStore.originalPath(version.fileHash(), version.fileExt());
            if (!Files.isRegularFile(original)) {
                throw new IOException("blob missing for version " + documentVersionId
                        + ": " + original);
            }

            docs.deleteLayoutForVersion(version.documentVersionId());

            if (isPdf(version)) {
                return layoutPdf(version, original, docs, repo, blobStore);
            }
            return layoutImagePlaceholder(version, original, docs, repo, blobStore);
        }
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

        java.util.Set<Integer> rastered = new java.util.HashSet<>();
        for (PdfLayoutExtractor.PdfPageImage image : extraction.images()) {
            blobStore.storePagePng(version.fileHash(), image.pageIndex(), image.png());
            rastered.add(image.pageIndex());
        }

        for (PageLayout page : extraction.pages()) {
            String imagePath = rastered.contains(page.pageIndex())
                    ? blobStore.pageRelativePath(version.fileHash(), page.pageIndex())
                    : null;
            long pageId = docs.insertPage(versionId, page.pageIndex(), page.widthPx(),
                    page.heightPx(), imagePath);
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
        }

        // Scanned PDFs have page rasters but no embedded text: keep ocr_required and
        // do not pretend digital layout succeeded.
        boolean ocrRequired = !extraction.anyText() || version.ocrRequired();
        String extractorId = extraction.anyText()
                ? PdfLayoutExtractor.EXTRACTOR_ID
                : "pdf-scan-page-raster";
        docs.updateVersionPageCount(versionId, extraction.pageCount(), ocrRequired);
        docs.updateVersionIngestStatus(versionId, "layout_ok");
        recordLayoutRun(docs, repo, version, extraction.pageCount(), blocks, spans, tables,
                extractorId);

        return new LayoutResult(versionId, extraction.pageCount(), blocks, spans, tables,
                ocrRequired, extractorId);
    }

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

    private LayoutResult layoutImagePlaceholder(DocumentRepository.DocumentVersionRow version,
            Path original, DocumentRepository docs, WorkspaceRepository repo, BlobStore blobStore)
            throws Exception {
        long versionId = version.documentVersionId();
        String rel = blobStore.pageRelativePath(version.fileHash(), 1);
        Path pagePng = blobStore.pagePath(version.fileHash(), 1);
        if (!Files.exists(pagePng)) {
            // Treat the original raster as the single page image until OCR (M5).
            Files.createDirectories(pagePng.getParent());
            Files.copy(original, pagePng, StandardCopyOption.REPLACE_EXISTING);
        }
        Optional<Long> pageId = docs.findPageId(versionId, 1);
        if (pageId.isEmpty()) {
            docs.insertPage(versionId, 1, null, null, rel);
        }
        docs.updateVersionPageCount(versionId, 1, true);
        docs.updateVersionIngestStatus(versionId, "layout_ok");
        recordLayoutRun(docs, repo, version, 1, 0, 0, 0, "image-placeholder");
        return new LayoutResult(versionId, 1, 0, 0, 0, true, "image-placeholder");
    }
}
