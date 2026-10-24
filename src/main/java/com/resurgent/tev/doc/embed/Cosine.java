package com.resurgent.tev.doc.embed;

/** Cosine similarity of two float vectors. Different lengths, or a zero vector, score 0. */
public final class Cosine {

    private Cosine() {}

    /**
     * @return similarity in [-1, 1], or 0 when either vector cannot be compared
     */
    public static double similarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
