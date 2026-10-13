package com.resurgent.tev.doc.classify;

import java.util.List;
import java.util.Optional;

/**
 * Optional LLM (or other) tie-break for ambiguous document classification (S2.12).
 * The offline default never touches the network and always declines, leaving the
 * document {@code unknown}. Implementations must stay usable in unit tests without
 * I/O when the offline instance is selected.
 */
@FunctionalInterface
public interface ClassifyTieBreak {

    /**
     * Attempts to pick one pack when deterministic scoring is tied or below the
     * confidence floor.
     *
     * @param context document inputs and scored candidates (never null)
     * @return the chosen pack, or empty to leave the document {@code unknown}
     */
    Optional<TieBreakChoice> resolve(TieBreakContext context);

    /** Never calls a network service; always returns empty. */
    static ClassifyTieBreak offline() {
        return context -> Optional.empty();
    }

    /**
     * Inputs available to a tie-break implementation. Labels and signals only —
     * not the full body.
     */
    record TieBreakContext(
            String fileRole,
            String fileName,
            String page1Text,
            String industryTag,
            List<ScoredPack> candidates) {

        public TieBreakContext {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /** One scored candidate pack presented to the tie-break. */
    record ScoredPack(String packCode, String packVersion, double score,
            List<String> matchedSignals, List<String> identitySignals) {

        public ScoredPack {
            matchedSignals = matchedSignals == null ? List.of() : List.copyOf(matchedSignals);
            identitySignals = identitySignals == null ? List.of() : List.copyOf(identitySignals);
        }

        /** Candidate without its pack identity signals (tests, callers that only score). */
        public ScoredPack(String packCode, String packVersion, double score,
                List<String> matchedSignals) {
            this(packCode, packVersion, score, matchedSignals, List.of());
        }
    }

    /** Winning pack chosen by a tie-break, with its own confidence and reason. */
    record TieBreakChoice(String packCode, double confidence, String reason) {

        public TieBreakChoice {
            if (packCode == null || packCode.isBlank()) {
                throw new IllegalArgumentException("packCode is required");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason is required");
            }
        }
    }
}
