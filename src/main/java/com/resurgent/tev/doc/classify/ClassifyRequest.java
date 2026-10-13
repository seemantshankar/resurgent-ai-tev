package com.resurgent.tev.doc.classify;

import com.resurgent.tev.doc.pack.DocTypePack;
import java.util.List;
import java.util.Objects;

/**
 * Inputs for {@link DocTypeClassifier#classify(ClassifyRequest)} (S2.12).
 * Layout text is supplied as already-gathered page-1 / heading text so scoring
 * stays pure; {@link DocTypeClassifier#loadPage1Text} gathers it from the
 * layout graph when a connection is available.
 *
 * @param documentVersionId version being classified; required for persistence
 * @param fileRole M1 {@code document_version.file_role}
 * @param fileName M1 {@code document_version.file_name}
 * @param page1Text whitespace-normalized page-1 text and title/heading blocks
 * @param industryTag optional industry hint (IndustryDetector or {@code --industry})
 * @param candidatePacks seed packs plus any pack-dir packs (never invented)
 * @param force when true, overwrite an existing classification
 */
public record ClassifyRequest(
        long documentVersionId,
        String fileRole,
        String fileName,
        String page1Text,
        String industryTag,
        List<DocTypePack> candidatePacks,
        boolean force) {

    public ClassifyRequest {
        Objects.requireNonNull(candidatePacks, "candidatePacks");
        candidatePacks = List.copyOf(candidatePacks);
    }

    /**
     * Score-only request (no document row to persist against). Persistence is
     * skipped; {@link #documentVersionId()} is {@code -1}.
     */
    public static ClassifyRequest scoreOnly(
            String fileRole,
            String fileName,
            String page1Text,
            String industryTag,
            List<DocTypePack> candidatePacks) {
        return new ClassifyRequest(-1L, fileRole, fileName, page1Text, industryTag,
                candidatePacks, false);
    }
}
