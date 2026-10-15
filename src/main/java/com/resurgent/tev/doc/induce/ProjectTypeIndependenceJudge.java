package com.resurgent.tev.doc.induce;

import java.util.List;

/**
 * Judges whether a document type seen across client document sets is
 * project-type independent — required of every borrower in the country whatever
 * the project — so it may join the country spine (M3-15, issue #148). The live
 * implementation asks the LLM; the offline implementation never judges anything
 * independent, so an offline run can extend an industry checklist but never the
 * spine. The learner only ever consults this seam for document types actually
 * observed in client sets from enough different industries — it never invents.
 */
public interface ProjectTypeIndependenceJudge {

    /** Stable adapter id, e.g. {@code llm-project-type-independence}. */
    String id();

    /**
     * Judges every candidate document type in one call.
     *
     * @param request the candidates observed across industries (required)
     * @return one judgement per candidate, in candidate order
     */
    IndependenceResult judge(IndependenceRequest request);

    /**
     * One document type observed in client document sets, with the industries it
     * was seen in.
     *
     * @param documentType pack code of the observed document type
     * @param industries the normalised industries whose mandates provided it
     */
    record Candidate(String documentType, List<String> industries) {

        public Candidate {
            industries = industries == null ? List.of() : List.copyOf(industries);
        }
    }

    /** The candidates to judge, in reading order (by document type). */
    record IndependenceRequest(List<Candidate> candidates) {

        public IndependenceRequest {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /**
     * One judgement.
     *
     * @param documentType pack code judged
     * @param independent whether the model judged the type project-type independent
     * @param reason one line why (recorded on the promoted item)
     */
    record Judgement(String documentType, boolean independent, String reason) {}

    /** The judgements, one per requested candidate when the model complied. */
    record IndependenceResult(List<Judgement> judgements) {

        public IndependenceResult {
            judgements = judgements == null ? List.of() : List.copyOf(judgements);
        }

        /** The judgement for one document type, or null when none was returned. */
        public Judgement judgementFor(String documentType) {
            for (Judgement judgement : judgements) {
                if (judgement.documentType().equals(documentType)) {
                    return judgement;
                }
            }
            return null;
        }
    }
}
