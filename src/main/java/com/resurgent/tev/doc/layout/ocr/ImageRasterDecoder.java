package com.resurgent.tev.doc.layout.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Decodes standalone screenshots (PNG, JPEG, WebP, single- and multi-frame TIFF)
 * into real pixel frames. Readers come from ImageIO, including plugins on the classpath.
 */
public final class ImageRasterDecoder {

    static {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("apple.awt.UIElement", "true");
    }

    public record Frame(BufferedImage image, int widthPx, int heightPx) {}

    private ImageRasterDecoder() {}

    public static List<Frame> decode(Path file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                throw new IOException("cannot open image " + file);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("no image reader for " + file.getFileName());
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int count;
                try {
                    count = reader.getNumImages(true);
                } catch (IOException e) {
                    count = 1;
                }
                List<Frame> frames = new ArrayList<>();
                if (count < 0) {
                    for (int i = 0; ; i++) {
                        try {
                            frames.add(readFrame(reader, i));
                        } catch (IndexOutOfBoundsException end) {
                            break;
                        }
                    }
                } else {
                    for (int i = 0; i < count; i++) {
                        frames.add(readFrame(reader, i));
                    }
                }
                if (frames.isEmpty()) {
                    throw new IOException("image has no frames: " + file.getFileName());
                }
                return frames;
            } finally {
                reader.dispose();
            }
        }
    }

    private static Frame readFrame(ImageReader reader, int index) throws IOException {
        BufferedImage image = reader.read(index);
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 1 || height < 1) {
            throw new IOException("frame " + index + " has no pixels");
        }
        return new Frame(image, width, height);
    }
}
