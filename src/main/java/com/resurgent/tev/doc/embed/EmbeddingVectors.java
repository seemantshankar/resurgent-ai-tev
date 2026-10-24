package com.resurgent.tev.doc.embed;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Little-endian float32 codec and vector arithmetic shared by storage and search. */
public final class EmbeddingVectors {

    private EmbeddingVectors() {}

    /** Encodes a vector as little-endian IEEE-754 float32. */
    public static byte[] toLittleEndian(float[] vector) {
        float[] values = vector == null ? new float[0] : vector;
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /** Decodes a little-endian IEEE-754 float32 blob. */
    public static float[] fromLittleEndian(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new float[0];
        }
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("embedding blob length " + bytes.length
                    + " is not a multiple of 4");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }

    /** L2-normalises {@code vector} in place. A zero vector is left unchanged. */
    public static void l2(float[] vector) {
        if (vector == null || vector.length == 0) {
            return;
        }
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        if (sum == 0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }

    /** Mean of same-length vectors, L2-normalised. Null when there is nothing to average. */
    public static float[] meanUnit(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return null;
        }
        int dims = -1;
        for (float[] vector : vectors) {
            if (vector != null && vector.length > 0) {
                dims = vector.length;
                break;
            }
        }
        if (dims < 0) {
            return null;
        }
        float[] sum = new float[dims];
        for (float[] vector : vectors) {
            if (vector == null || vector.length != dims) {
                continue;
            }
            for (int i = 0; i < dims; i++) {
                sum[i] += vector[i];
            }
        }
        l2(sum);
        return sum;
    }
}
