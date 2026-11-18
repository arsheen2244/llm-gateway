package dev.llmgateway.gateway.cache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Small helpers for embedding vectors. */
final class Vectors {

    private Vectors() {
    }

    /** For unit-length vectors the dot product is the cosine similarity: 1 = same direction. */
    static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector lengths differ: " + a.length + " vs " + b.length);
        }
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /** The binary form Redis vector search expects: 4-byte little-endian floats. */
    static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }
}
