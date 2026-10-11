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
 * <p>Lines come from PDFBox writing order. Blocks are paragraphs/heads split on
 * vertical gaps. A block whose lines have ≥2 consistent column gaps becomes a table.
 */
public final class PdfLayoutExtractor {

    public static final String EXTRACTOR_ID = "pdfbox-digital-text";

    /** Vertical gap (in text-height multiples) that starts a new paragraph. */
    private static final double PARAGRAPH_GAP_FACTOR = 0.85;

    /** A heading is a short first-of-group line with large font vs page median. */
    private static final double HEADING_SIZE_RATIO = 1.18;

    /** Render DPI for page images used by the UI + later OCR. */
    public static final int PAGE_RENDER_DPI = 300;

    static {
        // Keep Java2D page rasterization off the macOS Dock.
        System.setProperty("java.awt.headless", "true");
        System.setProperty("apple.awt.UIElement", "true");
    }

    public record PdfPageImage(int pageIndex, byte[] png, int widthPx, int heightPx) {}

    public record Extraction(List<PageLayout> pages, List<PdfPageImage> images, int pageCount,
            boolean anyText) {}

    /** Extract layout + page rasters for every page. */
    public Extraction extract(java.nio.file.Path pdfPath) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            int pageCount = doc.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(doc);
            List<PageLayout> pages = new ArrayList<>(pageCount);
            List<PdfPageImage> images = new ArrayList<>(pageCount);
            boolean anyText = false;
            for (int i = 0; i < pageCount; i++) {
                PDPage page = doc.getPage(i);
                BufferedImage image = renderer.renderImageWithDPI(i, PAGE_RENDER_DPI,
                        ImageType.RGB);
                byte[] png = encodePng(image);
                images.add(new PdfPageImage(i + 1, png, image.getWidth(), image.getHeight()));

                List<LayoutLine> lines = extractLines(doc, i + 1, page);
                if (!lines.isEmpty()) {
                    anyText = true;
                }
                pages.add(buildPageLayout(i + 1, page, image.getWidth(), image.getHeight(),
                        lines));
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

    private PageLayout buildPageLayout(int pageIndex, PDPage page, int widthPx, int heightPx,
            List<LayoutLine> lines) {
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

    private static double medianLineHeight(List<LayoutLine> lines) {
        List<Double> heights = new ArrayList<>(lines.size());
        for (LayoutLine line : lines) {
            heights.add(Math.max(line.height(), 1.0));
        }
        heights.sort(Comparator.naturalOrder());
        return heights.get(heights.size() / 2);
    }

    /** Cluster consecutive lines when the vertical gap stays under a paragraph threshold. */
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
     * Table heuristic: every line has the same count of word clusters (≥2), and those
     * clusters share column x-origins within a small tolerance.
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
