package com.resurgent.tev.doc.layout.ocr;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.layout.BBox;
import com.resurgent.tev.doc.layout.PdfLayoutExtractor;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns local OCR words into blocks, word spans, and coordinate-aligned table
 * cells when word boxes form a grid. Lines that read as a stamp or signature,
 * and words the engine marks handwritten, become their own blocks.
 */
public final class OcrGraphWriter {

    public static final String EXTRACTOR_ID = "local-ocr";

    private static final Pattern STAMP = Pattern.compile(
            "(?i).*\\b(stamp|seal|notary)\\b.*|.*(?:मुहर|मोहर).*");
    private static final Pattern SIGNATURE = Pattern.compile(
            "(?i).*\\b(signature|signatory|signed)\\b.*|.*हस्ताक्षर.*");

    public record Counts(int blocks, int spans, int tables) {}

    private OcrGraphWriter() {}

    public static Counts write(DocumentRepository docs, long pageId,
            LocalOcrClient.OcrPage page) throws SQLException {
        List<LocalOcrClient.OcrWord> printed = new ArrayList<>();
        List<LocalOcrClient.OcrWord> handwritten = new ArrayList<>();
        for (LocalOcrClient.OcrWord word : page.words()) {
            if (word.handwritten()) {
                handwritten.add(word);
            } else {
                printed.add(word);
            }
        }
        List<List<LocalOcrClient.OcrWord>> body = new ArrayList<>();
        List<List<LocalOcrClient.OcrWord>> stamps = new ArrayList<>();
        List<List<LocalOcrClient.OcrWord>> signatures = new ArrayList<>();
        for (List<LocalOcrClient.OcrWord> line : lines(printed)) {
            String text = join(line);
            if (STAMP.matcher(text).matches()) {
                stamps.add(line);
            } else if (SIGNATURE.matcher(text).matches()) {
                signatures.add(line);
            } else {
                body.add(line);
            }
        }
        int blocks = 0;
        int spans = 0;
        int tables = 0;
        int blockIndex = 0;
        if (isTable(body)) {
            Counts table = writeTable(docs, pageId, blockIndex, body);
            blocks += table.blocks();
            spans += table.spans();
            tables += table.tables();
            blockIndex += table.blocks();
        } else {
            for (List<LocalOcrClient.OcrWord> line : body) {
                spans += writeBlock(docs, pageId, blockIndex, "paragraph", line, false);
                blocks++;
                blockIndex++;
            }
        }
        for (List<LocalOcrClient.OcrWord> line : stamps) {
            spans += writeBlock(docs, pageId, blockIndex, "stamp", line, false);
            blocks++;
            blockIndex++;
        }
        for (List<LocalOcrClient.OcrWord> line : signatures) {
            spans += writeBlock(docs, pageId, blockIndex, "signature", line, false);
            blocks++;
            blockIndex++;
        }
        for (LocalOcrClient.OcrWord word : handwritten) {
            spans += writeBlock(docs, pageId, blockIndex, "handwriting", List.of(word), true);
            blocks++;
            blockIndex++;
        }
        return new Counts(blocks, spans, tables);
    }

    private static Counts writeTable(DocumentRepository docs, long pageId, int blockIndex,
            List<List<LocalOcrClient.OcrWord>> rows) throws SQLException {
        List<LocalOcrClient.OcrWord> flat = new ArrayList<>();
        for (List<LocalOcrClient.OcrWord> row : rows) {
            flat.addAll(row);
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        long blockId = insertLine(docs, pageId, blockIndex, "table", flat, false);
        writeSpans(docs, blockId, flat);
        long gridId = docs.insertTableGrid(blockId, rows.size(), cols, rows.isEmpty() ? 0 : 1);
        for (int r = 0; r < rows.size(); r++) {
            List<LocalOcrClient.OcrWord> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                LocalOcrClient.OcrWord word = row.get(c);
                docs.insertTableCell(gridId, r, c, word.text(),
                        word.bbox().x0(), word.bbox().y0(), word.bbox().x1(), word.bbox().y1(),
                        r == 0);
            }
        }
        return new Counts(1, flat.size(), 1);
    }

    private static int writeBlock(DocumentRepository docs, long pageId, int blockIndex, String kind,
            List<LocalOcrClient.OcrWord> words, boolean handwritten) throws SQLException {
        long blockId = insertLine(docs, pageId, blockIndex, kind, words, handwritten);
        writeSpans(docs, blockId, words);
        return words.size();
    }

    private static String join(List<LocalOcrClient.OcrWord> words) {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                raw.append(' ');
            }
            raw.append(words.get(i).text());
        }
        return raw.toString();
    }

    /**
     * A grid is at least two rows of at least two words whose column origins line up.
     */
    static boolean isTable(List<List<LocalOcrClient.OcrWord>> lines) {
        if (lines.size() < 2) {
            return false;
        }
        int cols = lines.get(0).size();
        if (cols < 2) {
            return false;
        }
        for (List<LocalOcrClient.OcrWord> line : lines) {
            if (line.size() < 2 || Math.abs(line.size() - cols) > 1) {
                return false;
            }
        }
        double height = lines.get(0).get(0).bbox().height();
        double tolerance = Math.max(height * 1.5, 12.0);
        for (int c = 0; c < cols; c++) {
            double anchor = lines.get(0).get(c).bbox().x0();
            for (List<LocalOcrClient.OcrWord> line : lines) {
                if (c >= line.size()) {
                    continue;
                }
                if (Math.abs(line.get(c).bbox().x0() - anchor) > tolerance) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long insertLine(DocumentRepository docs, long pageId, int blockIndex,
            String kind, List<LocalOcrClient.OcrWord> words, boolean handwritten)
            throws SQLException {
        BBox bbox = words.get(0).bbox();
        StringBuilder raw = new StringBuilder();
        double confidence = 0;
        for (int i = 0; i < words.size(); i++) {
            LocalOcrClient.OcrWord word = words.get(i);
            if (i > 0) {
                raw.append(' ');
            }
            raw.append(word.text());
            bbox = bbox.union(word.bbox());
            confidence += word.confidence();
        }
        String text = raw.toString();
        return docs.insertBlock(pageId, blockIndex, kind,
                bbox.x0(), bbox.y0(), bbox.x1(), bbox.y1(),
                text, PdfLayoutExtractor.normalizeWhitespace(text), blockIndex,
                EXTRACTOR_ID, handwritten, confidence / words.size());
    }

    private static void writeSpans(DocumentRepository docs, long blockId,
            List<LocalOcrClient.OcrWord> words) throws SQLException {
        for (int s = 0; s < words.size(); s++) {
            LocalOcrClient.OcrWord word = words.get(s);
            docs.insertSpan(blockId, s, word.text(),
                    word.bbox().x0(), word.bbox().y0(), word.bbox().x1(), word.bbox().y1(),
                    word.confidence());
        }
    }

    static List<List<LocalOcrClient.OcrWord>> lines(List<LocalOcrClient.OcrWord> words) {
        List<LocalOcrClient.OcrWord> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble((LocalOcrClient.OcrWord w) -> w.bbox().centerY())
                .thenComparingDouble(w -> w.bbox().x0()));
        List<List<LocalOcrClient.OcrWord>> lines = new ArrayList<>();
        List<LocalOcrClient.OcrWord> current = new ArrayList<>();
        double rowY = 0;
        for (LocalOcrClient.OcrWord word : sorted) {
            if (!current.isEmpty()) {
                double slack = Math.max(8.0, word.bbox().height() * 0.6);
                if (Math.abs(word.bbox().centerY() - rowY) > slack) {
                    current.sort(Comparator.comparingDouble(w -> w.bbox().x0()));
                    lines.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(word);
            rowY = word.bbox().centerY();
        }
        if (!current.isEmpty()) {
            current.sort(Comparator.comparingDouble(w -> w.bbox().x0()));
            lines.add(current);
        }
        return lines;
    }
}
