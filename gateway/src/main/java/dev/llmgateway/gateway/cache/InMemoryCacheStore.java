package dev.llmgateway.gateway.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache in this JVM's memory: for a single instance and for tests. Entries expire lazily
 * (checked when read), and the map is capped so it can't grow without limit; when full, new
 * answers are simply not stored. A real eviction policy (LRU) isn't worth it here: with
 * several instances the Redis store is the one to use.
 */
public class InMemoryCacheStore implements CacheStore {

    static final int MAX_ENTRIES = 10_000;

    private record Entry(CachedAnswer answer, long expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryCacheStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<CachedAnswer> getExact(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (clock.millis() >= entry.expiresAt()) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.answer());
    }

    @Override
    public void putExact(String key, CachedAnswer answer, Duration ttl) {
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(key)) {
            return;
        }
        entries.put(key, new Entry(answer, clock.millis() + ttl.toMillis()));
    }
}
