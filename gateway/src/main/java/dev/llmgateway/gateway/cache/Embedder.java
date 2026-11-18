package dev.llmgateway.gateway.cache;

/**
 * Turns text into a vector (an "embedding") whose direction captures its meaning, so two
 * prompts that mean the same thing point in nearly the same direction (design note 34).
 */
public interface Embedder {

    /**
     * @return a unit-length vector, so the cosine similarity of two of them is their dot product
     * @throws EmbeddingException if the service is down, slow, or answers something unusable;
     *                            the cache then treats the request as a semantic miss
     */
    float[] embed(String text);
}
