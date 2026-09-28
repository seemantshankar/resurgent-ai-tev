package com.resurgent.tev.parser.classify;

import java.util.List;

/** One Candidate's still-unbound cells, with Layer A about as the region brief. */
public record LayerBPrompt(
        String sheetName,
        String scheduleFamily,
        String about,
        String grid,
        List<String> allowedPaths,
        boolean retry) {
}
