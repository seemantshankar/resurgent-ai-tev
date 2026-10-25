package com.resurgent.tev.doc.extract;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Input to an {@link ExtractClient}. Paths point at the blob bundle so adapters can
 * send the original file (preferred) or page rasters.
 *
 * <p>{@code pageIndices} is the 1-based page selection to process ({@code --pages}).
 * An empty list means all pages. The live adapter sends a non-empty selection as
 * LlamaExtract {@code configuration.target_pages}; citations keep original page
 * numbers, so provenance is unaffected.
 */
public record ExtractRequest(
        Path pageBundleDir,
        Path originalFile,
        List<Integer> pageIndices,
        String langHint,
        String docTypeCode,
        String instructions,
        Map<String, String> options) {

    /**
     * Option whose value is the source lines the local passes could not read,
     * joined by newlines. Present only when a cloud model is asked for that remainder.
     */
    public static final String REMAINDER_LINES = "remainder_lines";

    public ExtractRequest {
        Objects.requireNonNull(pageBundleDir, "pageBundleDir");
        pageIndices = pageIndices == null ? List.of() : List.copyOf(pageIndices);
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
