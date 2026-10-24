package com.resurgent.tev.doc.layout.ocr;

import com.resurgent.tev.doc.layout.BBox;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Local OCR boundary used by layout for pages without a usable digital text
 * layer and for standalone images. Implementations must not call the network.
 * Tests supply a deterministic adapter; production uses a local Tesseract adapter.
 */
@FunctionalInterface
public interface LocalOcrClient {

    /**
     * Reads one page raster. Coordinates in the returned words are pixels of
     * that raster, origin at the top-left.
     *
     * @param image page image, normally a PNG
     * @param widthPx raster width
     * @param heightPx raster height
     * @throws IOException if the image cannot be read
     */
    OcrPage recognize(Path image, int widthPx, int heightPx) throws IOException;

    /**
     * Value stored on {@code page.ocr_engine} when this client produced the page.
     * Born-digital pages use {@code none} and never call {@link #recognize}.
     */
    default String engineId() {
        return "fixture";
    }

    /** One recognized word and its pixel box. Confidence is in {@code [0, 1]}. */
    record OcrWord(String text, BBox bbox, double confidence, boolean handwritten) {

        public OcrWord {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("OCR word text is required");
            }
            if (bbox == null) {
                throw new IllegalArgumentException("OCR word box is required");
            }
        }

        public OcrWord(String text, BBox bbox, double confidence) {
            this(text, bbox, confidence, false);
        }
    }

    /** Words from one page. An empty list is a successful read with no text. */
    record OcrPage(List<OcrWord> words) {

        public OcrPage {
            words = List.copyOf(words);
        }

        public static OcrPage empty() {
            return new OcrPage(List.of());
        }

        /** Mean word confidence, or 0 when the page has no words. */
        public double confidence() {
            return words.stream().mapToDouble(OcrWord::confidence).average().orElse(0);
        }
    }
}
