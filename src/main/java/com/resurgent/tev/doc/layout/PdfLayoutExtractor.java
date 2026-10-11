package com.resurgent.tev.doc.layout;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Born-digital PDF → layout graph (Architecture §7.1 layout stage).
 * Deterministic geometry from embedded text; no LLM, no meaning.
 *
 * <p>Page rasters are <em>not</em> produced here by default. A 300 DPI PNG is only
 * needed for scanned pages (OCR / UI highlight of image-only docs). Digital pages
 * keep text + bboxes in the layout graph and can be rasterized later on demand.
 */
public final class PdfLayoutExtractor {

    public static final String EXTRACTOR_ID = "pdfbox-digital-text";

    /** Vertical gap (in text-height multiples) that starts a new paragraph. */
    private static final double PARAGRAPH_GAP_FACTOR = 0.85;

    /** A heading is a short first-of-group line with large font vs page median. */
    private static final double HEADING_SIZE_RATIO = 1.18;

    /** Render DPI for page images used by the UI + OCR (scans only by default). */
    public static final int PAGE_RENDER_DPI = 300;

    static {
        // Keep Java2D page rasterization off the macOS Dock.
        System.setProperty("java.awt.headless", "true");
        System.setProperty("apple.awt.UIElement", "true");
    }

    public record PdfPageImage(int pageIndex, byte[] png, int widthPx, int heightPx) {}

    public record PageNeedsRaster(boolean raster, String reason) {}

    public record Extraction(List<PageLayout> pages, List<PdfPageImage> images, int pageCount,
            boolean anyText) {}

    /**
     * Text layout for every page. Page PNGs are produced only for pages that need
     * pixels (scan / embedded images / almost no text) — not for pure text pages.
     *
     * @return layouts for all pages with one-based indexes and only the selected
     *         300 DPI PNGs; anyText is true when at least one text line was collected
     * @throws IOException if opening, reading, rendering, encoding, or closing the PDF fails
     */
    public Extraction extractLayout(java.nio.file.Path pdfPath) throws IOException {
        return extract(pdfPath, false);
    }

    /**
     * Force 300 DPI rasters for every page (debug / full UI export).
     *
     * @return text layouts and PNGs for every page, using one-based page indexes
     * @throws IOException if opening, reading, rendering, encoding, or closing the PDF fails
     */
    public Extraction extractWithPageImages(java.nio.file.Path pdfPath) throws IOException {
        return extract(pdfPath, true);
    }

    /**
     * Requests a raster for fewer than 40 extracted characters or a directly
     * referenced image XObject. Exceptions inspecting resources also request a raster.
     *
     * @param textCharCount total character count of the page's extracted lines
     * @return the raster decision and its reason
     */
    public static PageNeedsRaster pageNeedsRaster(PDPage page, int textCharCount) {
        if (textCharCount < MIN_CHARS_FOR_TEXT_PAGE) {
            return new PageNeedsRaster(true,
                    "low_text(" + textCharCount + " chars)");
        }
        if (hasImageXObject(page)) {
            return new PageNeedsRaster(true, "image_xobject");
        }
        return new PageNeedsRaster(false, "text_layer");
    }

    /** Below this many extracted characters a page is treated as scan/graphic. */
    public static final int MIN_CHARS_FOR_TEXT_PAGE = 40;

    /**
     * Checks the page's direct XObjects for an image. Missing resources return
     * false; exceptions while inspecting resources conservatively return true.
     */
    private static boolean hasImageXObject(PDPage page) {
        try {
            var resources = page.getResources();
            if (resources == null) {
                return false;
            }
            for (var name : resources.getXObjectNames()) {
                var xobj = resources.getXObject(name);
                if (xobj instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Defensive: a malformed XObject table should not fail layout.
            return true;
        }
        return false;
    }

    private Extraction extract(java.nio.file.Path pdfPath, boolean forceAllImages)
            throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            int pageCount = doc.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(doc);
            List<PageLayout> pages = new ArrayList<>(pageCount);
            List<PdfPageImage> images = new ArrayList<>();
            boolean anyText = false;
            for (int i = 0; i < pageCount; i++) {
                PDPage page = doc.getPage(i);
                List<LayoutLine> lines = extractLines(doc, i + 1, page);
                int textChars = lines.stream().mapToInt(l -> l.text().length()).sum();
                if (!lines.isEmpty()) {
                    anyText = true;
                }
                PageNeedsRaster need = forceAllImages
                        ? new PageNeedsRaster(true, "forced")
                        : pageNeedsRaster(page, textChars);

                Integer widthPx;
                Integer heightPx;
                if (need.raster()) {
                    BufferedImage image = renderer.renderImageWithDPI(i, PAGE_RENDER_DPI,
                            ImageType.RGB);
                    byte[] png = encodePng(image);
                    widthPx = image.getWidth();
                    heightPx = image.getHeight();
                    images.add(new PdfPageImage(i + 1, png, widthPx, heightPx));
                } else {
                    double scale = PAGE_RENDER_DPI / 72.0;
                    widthPx = (int) Math.round(page.getMediaBox().getWidth() * scale);
                    heightPx = (int) Math.round(page.getMediaBox().getHeight() * scale);
                }
                pages.add(buildPageLayout(i + 1, page, widthPx, heightPx, lines));
            }
            return new Extraction(pages, images, pageCount, anyText);
        }
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(image, "PNG", out)) {
            throw new IOException("PNG writer unavailable");
        }
        return out.toByteArray();
    }

    private List<LayoutLine> extractLines(PDDocument doc, int pageIndex, PDPage page)
            throws IOException {
        LineCollector collector = new LineCollector(pageIndex);
        collector.setSortByPosition(true);
        collector.getText(doc); // runs writeString per visual line
        return collector.lines();
    }

    private PageLayout buildPageLayout(int pageIndex, PDPage page, Integer widthPx,
            Integer heightPx, List<LayoutLine> lines) {
        float heightPt = page.getMediaBox().getHeight();
        float widthPt = page.getMediaBox().getWidth();
        if (lines.isEmpty()) {
            return PageLayout.empty(pageIndex, widthPt, heightPt, widthPx, heightPx);
        }
        double medianHeight = medianLineHeight(lines);
        List<List<LayoutLine>> groups = groupLines(lines, medianHeight);
        List<LayoutBlock> blocks = new ArrayList<>();
        for (List<LayoutLine> group : groups) {
            blocks.add(buildBlock(group, medianHeight));
        }
        return new PageLayout(pageIndex, widthPt, heightPt, widthPx, heightPx, blocks);
    }

    /**
     * Returns the upper median of a nonempty list of line heights, each clamped
     * to at least one PDF point.
     */
    private static double medianLineHeight(List<LayoutLine> lines) {
        List<Double> heights = new ArrayList<>(lines.size());
        for (LayoutLine line : lines) {
            heights.add(Math.max(line.height(), 1.0));
        }
        heights.sort(Comparator.naturalOrder());
        return heights.get(heights.size() / 2);
    }

    /**
     * Groups lines in top-to-bottom, then left-to-right order, splitting on
     * vertical gaps or sufficiently large indents accompanied by a smaller gap.
     */
    private static List<List<LayoutLine>> groupLines(List<LayoutLine> lines, double medianHeight) {
        List<LayoutLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(LayoutLine::yCenter)
                .thenComparingDouble(l -> l.bbox().x0()));
        List<List<LayoutLine>> groups = new ArrayList<>();
        List<LayoutLine> current = new ArrayList<>();
        LayoutLine prev = null;
        for (LayoutLine line : sorted) {
            if (prev != null) {
                double gap = line.bbox().y0() - prev.bbox().y1();
                double threshold = Math.max(prev.height(), medianHeight) * PARAGRAPH_GAP_FACTOR;
                boolean newPara = gap > threshold
                        || line.bbox().x0() > prev.bbox().x0() + Math.max(prev.height(), 8) * 3
                                && gap > medianHeight * 0.25;
                // A large indent after a small gap still continues the paragraph;
                // a clear vertical gap always splits.
                if (gap > threshold) {
                    groups.add(current);
                    current = new ArrayList<>();
                } else if (newPara && !current.isEmpty()
                        && line.bbox().x0() - prev.bbox().x0() > Math.max(prev.height(), 8) * 4
                        && line.bbox().y0() - prev.bbox().y1() > medianHeight * 0.35) {
                    groups.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(line);
            prev = line;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private static LayoutBlock buildBlock(List<LayoutLine> group, double medianHeight) {
        BBox bbox = group.get(0).bbox();
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < group.size(); i++) {
            if (i > 0) {
                raw.append('\n');
            }
            raw.append(group.get(i).text());
            bbox = bbox.union(group.get(i).bbox());
        }
        String textRaw = raw.toString();
        String textNorm = normalizeWhitespace(textRaw);

        List<List<String>> tableRows = tryTableRows(group);
        if (tableRows != null) {
            List<BBox> rowBoxes = new ArrayList<>(group.size());
            for (LayoutLine line : group) {
                rowBoxes.add(line.bbox());
            }
            return LayoutBlock.table(bbox, textRaw, textNorm, group, tableRows, rowBoxes);
        }

        boolean heading = group.size() == 1
                && group.get(0).height() >= medianHeight * HEADING_SIZE_RATIO
                && textNorm.length() <= 120
                && !textNorm.endsWith(".");
        return heading
                ? LayoutBlock.heading(bbox, textRaw, textNorm, group)
                : LayoutBlock.paragraph(bbox, textRaw, textNorm, group);
    }

    /**
     * Recognizes at least two rows with at least two spans each and aligned
     * column x-origins. Later rows may differ from the first row by one span;
     * output uses the first row's width, padding missing cells and dropping extras.
     *
     * @return trimmed cell text by row, or null when the table heuristic fails
     */
    private static List<List<String>> tryTableRows(List<LayoutLine> group) {
        if (group.size() < 2) {
            return null;
        }
        List<List<LayoutSpan>> rows = new ArrayList<>(group.size());
        int expectedCols = -1;
        for (LayoutLine line : group) {
            List<LayoutSpan> words = line.spans();
            if (words.size() < 2) {
                return null;
            }
            if (expectedCols < 0) {
                expectedCols = words.size();
            } else if (words.size() != expectedCols) {
                // Allow ragged last column only when the leading columns align.
                if (words.size() < 2 || Math.abs(words.size() - expectedCols) > 1) {
                    return null;
                }
            }
            rows.add(words);
        }
        if (expectedCols < 2) {
            return null;
        }
        // Check column alignment across the first min-width row set.
        int cols = expectedCols;
        double medianHeight = group.get(0).height();
        double tol = Math.max(medianHeight * 0.6, 6.0);
        for (int c = 0; c < cols; c++) {
            Double anchor = null;
            for (List<LayoutSpan> row : rows) {
                if (c >= row.size()) {
                    continue;
                }
                double x = row.get(c).bbox().x0();
                if (anchor == null) {
                    anchor = x;
                } else if (Math.abs(x - anchor) > tol * 2.5) {
                    return null;
                }
            }
        }
        List<List<String>> table = new ArrayList<>(rows.size());
        for (List<LayoutSpan> row : rows) {
            List<String> cells = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++) {
                cells.add(c < row.size() ? row.get(c).text().trim() : "");
            }
            table.add(cells);
        }
        return table;
    }

    /**
     * Collapses Java regex whitespace runs to a single space and trims the result;
     * returns null for null input.
     */
    public static String normalizeWhitespace(String text) {
        return text == null ? null : text.replaceAll("\\s+", " ").trim();
    }

    /** PDFBox stripper that keeps each writeString line with word boxes. */
    private static final class LineCollector extends PDFTextStripper {

        private final List<LayoutLine> lines = new ArrayList<>();
        private final int pageIndex;

        LineCollector(int pageIndex) throws IOException {
            super();
            this.pageIndex = pageIndex;
            setStartPage(pageIndex);
            setEndPage(pageIndex);
        }

        List<LayoutLine> lines() {
            return lines;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions)
                throws IOException {
            super.writeString(text, textPositions);
            if (textPositions == null || textPositions.isEmpty()) {
                return;
            }
            String raw = text;
            // PDFBox may include a trailing newline in the line string.
            if (raw.endsWith("\n") || raw.endsWith("\r")) {
                raw = raw.replace("\r", "").replace("\n", "");
            }
            if (raw.isBlank()) {
                return;
            }
            List<LayoutSpan> spans = wordSpans(textPositions);
            if (spans.isEmpty()) {
                return;
            }
            BBox bbox = spans.get(0).bbox();
            for (int i = 1; i < spans.size(); i++) {
                bbox = bbox.union(spans.get(i).bbox());
            }
            lines.add(new LayoutLine(raw, bbox, spans));
        }

        private static List<LayoutSpan> wordSpans(List<TextPosition> textPositions) {
            List<LayoutSpan> spans = new ArrayList<>();
            StringBuilder word = new StringBuilder();
            Double wordX0 = null;
            Double wordY0 = null;
            Double wordX1 = null;
            Double wordY1 = null;
            for (TextPosition tp : textPositions) {
                String unicode = tp.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                boolean isSpace = unicode.chars().allMatch(Character::isWhitespace);
                float x0 = tp.getXDirAdj();
                float x1 = x0 + tp.getWidthDirAdj();
                // PDFBox YDirAdj grows downward from the top of the page.
                float y1 = tp.getYDirAdj();
                float y0 = y1 - tp.getHeightDir();
                if (isSpace) {
                    if (word.length() > 0) {
                        spans.add(new LayoutSpan(word.toString(),
                                new BBox(wordX0, wordY0, wordX1, wordY1)));
                        word.setLength(0);
                        wordX0 = wordY0 = wordX1 = wordY1 = null;
                    }
                    continue;
                }
                if (word.length() == 0) {
                    wordX0 = (double) x0;
                    wordY0 = (double) y0;
                    wordX1 = (double) x1;
                    wordY1 = (double) y1;
                } else {
                    wordX0 = Math.min(wordX0, x0);
                    wordY0 = Math.min(wordY0, y0);
                    wordX1 = Math.max(wordX1, x1);
                    wordY1 = Math.max(wordY1, y1);
                }
                word.append(unicode);
            }
            if (word.length() > 0) {
                spans.add(new LayoutSpan(word.toString(),
                        new BBox(wordX0, wordY0, wordX1, wordY1)));
            }
            return spans;
        }
    }
}
