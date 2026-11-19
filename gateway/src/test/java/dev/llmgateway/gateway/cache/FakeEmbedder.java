package dev.llmgateway.gateway.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * An embedder for tests: each known text maps to a vector the test chose, so a test decides
 * exactly how similar two prompts are. Unknown text gets its own direction, unlike any other.
 */
final class FakeEmbedder implements Embedder {

    static final int DIMENSIONS = 384;

    private final Map<String, float[]> vectors = new HashMap<>();
    private int nextUnusedAxis = 100;
    int calls;
    boolean failing;

    /**
     * Gives {@code text} a unit vector at {@code similarity} to axis 0: texts given 1.0 and 0.9
     * have cosine similarity 0.9 with each other.
     */
    FakeEmbedder text(String text, double similarity) {
        float[] vector = new float[DIMENSIONS];
        vector[0] = (float) similarity;
        vector[1] = (float) Math.sqrt(1 - similarity * similarity);
        vectors.put(text, vector);
        return this;
    }

    @Override
    public float[] embed(String text) {
        calls++;
        if (failing) {
            throw new EmbeddingException("embedding service down");
        }
        return vectors.computeIfAbsent(text, t -> {
            float[] vector = new float[DIMENSIONS];
            vector[nextUnusedAxis++] = 1f;
            return vector;
        });
    }
}
