package dev.llmgateway.gateway.cache;

import java.time.Duration;
import java.util.Optional;

/** Where cached answers live: this instance's memory, or Redis (shared by all instances). */
public interface CacheStore {

    Optional<CachedAnswer> getExact(String key);

    /** Stores an answer that expires after {@code ttl}. */
    void putExact(String key, CachedAnswer answer, Duration ttl);

    /**
     * The most similar cached prompt within {@code scope} (design note 32), if its cosine
     * similarity is at least {@code minSimilarity}. Vectors are unit length.
     */
    Optional<SemanticMatch> findSimilar(String scope, float[] vector, double minSimilarity);

    /** Stores an answer under its prompt's embedding, within {@code scope}. */
    void putSemantic(String scope, float[] vector, CachedAnswer answer, Duration ttl);
}
