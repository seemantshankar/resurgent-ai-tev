package com.resurgent.tev.parser.classify;

/** Narrow port for Packet classification. Tests inject a fake; no live provider required. */
public interface ClassifierLlm {

    LayerAJudgment classifyLayerA(LayerAPrompt prompt);
}
