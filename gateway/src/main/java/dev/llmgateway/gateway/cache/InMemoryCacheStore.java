package dev.llmgateway.gateway.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cache in this JVM's memory: for a single instance and for tests. Entries expire lazily
 * (checked when read), and each layer is capped so it can't grow without limit; when full, new
 * answers are simply not stored. A real eviction policy (LRU) isn't worth it here: with
 * several instances the Redis store is the one to use.
 *
 * <p>The semantic layer compares the prompt with every cached prompt in the same scope (a
 * linear scan). Fine for thousands of entries; Redis does the same search in its own index.
 */
public class InMemoryCacheStore implements CacheStore {

    static final int MAX_ENTRIES = 10_000;

    private record Entry(CachedAnswer answer, long expiresAt) {
    }

    private record VectorEntry(float[] vector, CachedAnswer answer, long expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, List<VectorEntry>> vectorsByScope = new ConcurrentHashMap<>();
    private final AtomicInteger vectorCount = new AtomicInteger();
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

    @Override
    public Optional<SemanticMatch> findSimilar(String scope, float[] vector, double minSimilarity) {
        List<VectorEntry> candidates = vectorsByScope.get(scope);
        if (candidates == null) {
            return Optional.empty();
        }
        long now = clock.millis();
        if (candidates.removeIf(entry -> now >= entry.expiresAt())) {
            vectorCount.set(vectorsByScope.values().stream().mapToInt(List::size).sum());
        }
        SemanticMatch best = null;
        for (VectorEntry entry : candidates) {
            double similarity = Vectors.dot(vector, entry.vector());
            if (similarity >= minSimilarity && (best == null || similarity > best.similarity())) {
                best = new SemanticMatch(entry.answer(), similarity);
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public void putSemantic(String scope, float[] vector, CachedAnswer answer, Duration ttl) {
        if (vectorCount.get() >= MAX_ENTRIES) {
            return;
        }
        vectorsByScope.computeIfAbsent(scope, s -> new CopyOnWriteArrayList<>())
                .add(new VectorEntry(vector.clone(), answer, clock.millis() + ttl.toMillis()));
        vectorCount.incrementAndGet();
    }
}
