package com.resurgent.tev.parser.classify;

import java.util.List;

/** Narrow port for Packet classification. Tests inject a fake; no live provider required. */
public interface ClassifierLlm {

    LayerAJudgment classifyLayerA(LayerAPrompt prompt);

    /** Money-line bindings for one Packet. Coverage-parent cheap pass never calls this. */
    List<LayerBLineJudgment> classifyLayerB(LayerBPrompt prompt);
}
