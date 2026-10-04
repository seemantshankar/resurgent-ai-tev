package com.resurgent.tev.parser.classify;

/**
 * Port for a decision model (Liquid D1): picks a kind and a scale for one cell from fixed
 * option sets and reports how sure it is. It generates no text, so it cannot name a unit or
 * a currency; those come from the labels.
 */
interface CellDecisionClient {

    /**
     * One cell's pick; each confidence is the model's own, 0.0-1.0. {@code scale} is null (and its
     * confidence 0) when the scale question was not asked.
     */
    record Decision(String kind, double kindConfidence, String scale, double scaleConfidence) {
        /** The weaker of the two answers: what a cell must clear when both questions matter. */
        double confidence() {
            return Math.min(kindConfidence, scaleConfidence);
        }

        /**
         * Scale only means something for money, and a scale the sheet or region states wins over
         * the model's anyway: only then is the scale answer left out of the gate.
         */
        double confidenceFor(CellScale statedScale) {
            return ReadingOutcome.MONEY.equals(kind) && statedScale == null ? confidence() : kindConfidence;
        }

        boolean settles(double threshold, CellScale statedScale) {
            return confidenceFor(statedScale) >= threshold;
        }
    }

    /** Decide from the cell's plain-text description; throws when the call or its answer fails. */
    Decision decide(String state) throws Exception;

    /** As {@link #decide(String)}; {@code askScale} false leaves the scale question out of the request. */
    default Decision decide(String state, boolean askScale) throws Exception {
        return decide(state);
    }
}
