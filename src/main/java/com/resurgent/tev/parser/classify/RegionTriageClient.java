package com.resurgent.tev.parser.classify;

import java.util.Map;

/**
 * A decision model's independent opinion on whether a region is part of what its sheet represents.
 * It sees a summary of the region and never the first-pass label: shown that label, the model
 * mostly repeats it (77 scratch calls against 30 in the shadow test) and stops being a second look.
 */
interface RegionTriageClient {

    /** {@code choice} is main, scratch or orphan; {@code confidence} is the model's own, 0.0-1.0. */
    record Opinion(String choice, double confidence, Map<String, Double> probabilities) {}

    String model();

    Opinion decide(String stateJson) throws Exception;
}
