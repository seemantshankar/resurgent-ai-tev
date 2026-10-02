package com.resurgent.tev.parser.classify;

/**
 * Port for a decision model (Liquid D1): picks a kind and a scale for one cell from fixed
 * option sets and reports how sure it is. It generates no text, so it cannot name a unit or
 * a currency; those come from the labels.
 */
interface CellDecisionClient {

    /** One cell's pick; each confidence is the model's own, 0.0-1.0. */
    record Decision(String kind, double kindConfidence, String scale, double scaleConfidence) {
        double confidence() {
            return Math.min(kindConfidence, scaleConfidence);
        }
    }

    /** Decide from the cell's plain-text description; throws when the call or its answer fails. */
    Decision decide(String state) throws Exception;
}
