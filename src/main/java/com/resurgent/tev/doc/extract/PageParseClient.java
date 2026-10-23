package com.resurgent.tev.doc.extract;

import java.nio.file.Path;
import java.util.List;

/**
 * Cloud vision parse boundary. {@code targetPages} is the 1-based subset that
 * failed page triage; null or empty means the whole file. Tests record the
 * indices. Production is {@link LlamaParseClient}.
 */
public interface PageParseClient {

    LlamaParseClient.ParseJob parse(Path file, ParseTier tier, List<Integer> targetPages)
            throws ExtractException;
}
