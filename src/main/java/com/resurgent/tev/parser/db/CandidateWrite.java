package com.resurgent.tev.parser.db;

/**
 * Fields for inserting a Candidate (ADR 0016). Members are supplied separately as cell ids.
 */
public record CandidateWrite(
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
        String explanation) {
}
