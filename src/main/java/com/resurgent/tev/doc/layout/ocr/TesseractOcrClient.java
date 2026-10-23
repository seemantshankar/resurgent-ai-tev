package com.resurgent.tev.doc.layout.ocr;

import com.resurgent.tev.doc.layout.BBox;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local Tesseract CLI adapter ({@code tesseract … tsv}). No network.
 * When the binary is not on {@code PATH}, {@link #recognize} returns an empty
 * page so layout still records an attempt without failing the digital path.
 */
public final class TesseractOcrClient implements LocalOcrClient {

    public static final String ENGINE_ID = "tesseract";

    private static final AtomicReference<Optional<Path>> BINARY = new AtomicReference<>();
    private volatile List<String> installedPacks;

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public OcrPage recognize(Path image, int widthPx, int heightPx) throws IOException {
        Optional<Path> binary = binary();
        if (binary.isEmpty()) {
            return OcrPage.empty();
        }
        List<String> langs = OcrLanguagePacks.select(System.getenv("TEV_OCR_LANGS"), this::packInstalled);
        ProcessBuilder builder = new ProcessBuilder(
                binary.get().toString(),
                image.toAbsolutePath().toString(),
                "stdout",
                "-l", String.join("+", langs),
                "--psm", "6",
                "tsv");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        String tsv;
        try (var in = process.getInputStream()) {
            tsv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("tesseract interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("tesseract timed out for " + image);
        }
        if (process.exitValue() != 0) {
            return OcrPage.empty();
        }
        return parseTsv(tsv);
    }

    /**
     * Parses Tesseract TSV. Word rows are level 5 with a non-negative confidence.
     * Confidence is scaled from the 0–100 TSV value into {@code [0, 1]}.
     */
    public static OcrPage parseTsv(String tsv) {
        if (tsv == null || tsv.isBlank()) {
            return OcrPage.empty();
        }
        List<OcrWord> words = new ArrayList<>();
        for (String line : tsv.split("\\R")) {
            if (line.isBlank() || line.startsWith("level")) {
                continue;
            }
            String[] col = line.split("\t", -1);
            if (col.length < 12 || !"5".equals(col[0])) {
                continue;
            }
            String text = col[11].trim();
            if (text.isEmpty()) {
                continue;
            }
            double conf;
            try {
                conf = Double.parseDouble(col[10]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (conf < 0) {
                continue;
            }
            int left = Integer.parseInt(col[6]);
            int top = Integer.parseInt(col[7]);
            int width = Integer.parseInt(col[8]);
            int height = Integer.parseInt(col[9]);
            words.add(new OcrWord(text, new BBox(left, top, left + width, top + height), conf / 100.0));
        }
        return new OcrPage(words);
    }

    static Optional<Path> binary() {
        Optional<Path> cached = BINARY.get();
        if (cached != null) {
            return cached;
        }
        BINARY.compareAndSet(null, locateBinary());
        return BINARY.get();
    }

    private static Optional<Path> locateBinary() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, "tesseract");
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private boolean packInstalled(String code) {
        List<String> packs = installedPacks;
        if (packs == null) {
            packs = listPacks();
            installedPacks = packs;
        }
        return packs.contains(code);
    }

    private List<String> listPacks() {
        Optional<Path> binary = binary();
        if (binary.isEmpty()) {
            return List.of();
        }
        ProcessBuilder builder = new ProcessBuilder(binary.get().toString(), "--list-langs");
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            List<String> codes = new ArrayList<>();
            for (String line : output.split("\\R")) {
                String code = line.trim().toLowerCase(java.util.Locale.ROOT);
                if (code.isEmpty() || code.startsWith("list of")) {
                    continue;
                }
                codes.add(code);
            }
            return List.copyOf(codes);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }
}
