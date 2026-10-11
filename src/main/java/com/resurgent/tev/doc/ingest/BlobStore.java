package com.resurgent.tev.doc.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Content-addressed binary store (Architecture D2):
 * {@code blobs/{sha256}/original.ext} and {@code blobs/{sha256}/pages/pNNN.png}.
 * Never large binaries in SQLite — only sha256 + relative path.
 */
public final class BlobStore {

    private final Path root;

    /** @param root directory that will contain {@code {sha256}/...} folders */
    public BlobStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** Relative path stored in {@code page.image_path} / version metadata. */
    public String originalRelativePath(String sha256, String ext) {
        return sha256 + "/original" + normalizeExt(ext);
    }

    public String pageRelativePath(String sha256, int pageIndex) {
        return String.format(Locale.ROOT, "%s/pages/p%03d.png", sha256, pageIndex);
    }

    public Path originalPath(String sha256, String ext) {
        return root.resolve(originalRelativePath(sha256, ext));
    }

    public Path pagePath(String sha256, int pageIndex) {
        return root.resolve(pageRelativePath(sha256, pageIndex));
    }

    /**
     * Writes immutable original bytes. If the blob already exists it is left alone
     * (same sha256 ⇒ same content).
     */
    public Path storeOriginal(String sha256, String ext, Path source) throws IOException {
        Path target = originalPath(sha256, ext);
        if (Files.exists(target)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    public Path storePagePng(String sha256, int pageIndex, byte[] pngBytes) throws IOException {
        Path target = pagePath(sha256, pageIndex);
        Files.createDirectories(target.getParent());
        Files.write(target, pngBytes);
        return target;
    }

    private static String normalizeExt(String ext) {
        if (ext == null || ext.isBlank()) {
            return "";
        }
        return ext.startsWith(".") ? ext.toLowerCase(Locale.ROOT)
                : "." + ext.toLowerCase(Locale.ROOT);
    }

    /** Default sibling of the workspace database: {@code <parent>/blobs}. */
    public static BlobStore besideDatabase(Path dbPath) {
        Path parent = dbPath.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new UncheckedIOException(new IOException("db path has no parent: " + dbPath));
        }
        return new BlobStore(parent.resolve("blobs"));
    }
}
