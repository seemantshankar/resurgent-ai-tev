package com.resurgent.tev.doc.induce;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic offline project-type judgement (the M2 fixture precedent): no
 * candidate is judged project-type independent, so an offline learning run can
 * extend an industry checklist from the client document sets but can never put a
 * new item on the country spine — that decision needs a model's judgement, and
 * without one the safe answer is "not independent", with the reason recorded.
 */
public final class FixtureProjectTypeIndependenceJudge
        implements ProjectTypeIndependenceJudge {

    static final String OFFLINE_REASON =
            "offline: no model judged project-type independence";

    @Override
    public String id() {
        return "fixture-project-type-independence";
    }

    @Override
    public IndependenceResult judge(IndependenceRequest request) {
        List<Judgement> judgements = new ArrayList<>();
        for (Candidate candidate : request.candidates()) {
            judgements.add(new Judgement(
                    candidate.documentType(), false, OFFLINE_REASON));
        }
        return new IndependenceResult(judgements);
    }
}
