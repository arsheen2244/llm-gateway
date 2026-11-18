package dev.llmgateway.gateway.cache;

import java.time.Duration;
import java.util.Optional;

/** Where cached answers live: this instance's memory, or Redis (shared by all instances). */
public interface CacheStore {

    Optional<CachedAnswer> getExact(String key);

    /** Stores an answer that expires after {@code ttl}. */
    void putExact(String key, CachedAnswer answer, Duration ttl);
}
