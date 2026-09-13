package com.resurgent.tev.parser.classify;

/** Production default until a live provider is wired. Tests inject {@code FakeClassifierLlm}. */
public final class UnconfiguredClassifierLlm implements ClassifierLlm {

    @Override
    public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
        throw new IllegalStateException("no LLM provider configured");
    }
}
