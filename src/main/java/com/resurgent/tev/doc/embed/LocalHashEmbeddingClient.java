package com.resurgent.tev.doc.embed;

import java.util.Locale;

/**
 * Deterministic offline vectoriser: signed hashing of within-word character
 * trigrams, word unigrams, and word bigrams into a fixed {@code float32} space,
 * then L2-normalised. No network, no randomness, no clock.
 */
public final class LocalHashEmbeddingClient implements EmbeddingClient {

    /** Model id persisted on rows this client writes. */
    public static final String MODEL = "local-ngram-hash-v1";

    /** Fixed width of every local vector. */
    public static final int DIMS = 128;

    private static final int FNV_OFFSET = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public int dims() {
        return DIMS;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMS];
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return vector;
        }
        String[] words = normalized.split(" ");
        for (String word : words) {
            add(vector, "w1:" + word, 1f);
            if (word.length() >= 4) {
                for (int i = 0; i + 3 <= word.length(); i++) {
                    add(vector, "c3:" + word.substring(i, i + 3), 1f);
                }
            }
        }
        for (int i = 0; i + 1 < words.length; i++) {
            add(vector, "w2:" + words[i] + " " + words[i + 1], 2f);
        }
        EmbeddingVectors.l2(vector);
        return vector;
    }

    /** Lowercase letters and digits, punctuation collapsed to single spaces. */
    static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean pendingSpace = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(c);
            } else {
                pendingSpace = true;
            }
        }
        return out.toString();
    }

    private static void add(float[] vector, String token, float weight) {
        int hash = fnv1a(token);
        int index = (hash & 0x7fffffff) % vector.length;
        float signed = (hash & 0x80000000) != 0 ? -weight : weight;
        vector[index] += signed;
    }

    /** FNV-1a 32-bit. Stable across JVMs; not {@link String#hashCode()}. */
    static int fnv1a(String token) {
        int hash = FNV_OFFSET;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
