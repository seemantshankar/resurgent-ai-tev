package com.resurgent.tev.doc.extract;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Input to an {@link ExtractClient}. Paths point at the blob bundle so adapters can
 * send the original file (preferred) or page rasters.
 */
public record ExtractRequest(
        Path pageBundleDir,
        Path originalFile,
        List<Integer> pageIndices,
        String langHint,
        String docTypeCode,
        String instructions,
        Map<String, String> options) {

    public ExtractRequest {
        Objects.requireNonNull(pageBundleDir, "pageBundleDir");
        pageIndices = pageIndices == null ? List.of() : List.copyOf(pageIndices);
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
