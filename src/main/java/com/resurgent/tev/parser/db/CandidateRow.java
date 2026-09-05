package com.resurgent.tev.parser.db;

/**
 * Persisted Candidate row (ADR 0016). Members are loaded via
 * {@link WorkspaceRepository#selectCandidateMemberCellIds(long)}.
 */
public record CandidateRow(
        long candidateId,
        long parseRunId,
        long worksheetId,
        String candidateKind,
        Long parentCandidateId,
        Integer bboxMinRow,
        Integer bboxMinCol,
        Integer bboxMaxRow,
        Integer bboxMaxCol,
        String internalWhitespaceJson,
        String anchorsJson,
        String structuralSignaturesJson,
        boolean isolatedHiddenWorksheet,
        Double structuralConfidence,
        String structuralConfidenceRationale,
        String explanation,
        String createdAt) {
}
