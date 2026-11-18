package dev.llmgateway.gateway.cache;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Cache in Redis, shared by every gateway instance.
 *
 * <ul>
 *   <li><b>Exact layer:</b> one JSON string per answer under {@code cache:exact:<key>}.</li>
 *   <li><b>Semantic layer (design note 35):</b> one hash per answer under
 *       {@code cache:sem:<uuid>} with the scope (a TAG), the prompt's embedding (a VECTOR) and
 *       the answer. A Redis search index over those hashes finds the nearest prompt within a
 *       scope.</li>
 * </ul>
 * Every entry has a Redis TTL, so expiry needs no cleanup job; expired hashes leave the index.
 */
public class RedisCacheStore implements CacheStore {

    static final String EXACT_PREFIX = "cache:exact:";
    static final String SEMANTIC_PREFIX = "cache:sem:";
    static final String INDEX = "cache-semantic";

    private static final byte[] SEARCH_SCRIPT = load("cache/semantic_search.lua");

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final int dimensions;
    private volatile boolean indexReady;

    public RedisCacheStore(StringRedisTemplate redis, ObjectMapper json, int dimensions) {
        this.redis = redis;
        this.json = json;
        this.dimensions = dimensions;
    }

    @Override
    public Optional<CachedAnswer> getExact(String key) {
        return read(redis.opsForValue().get(EXACT_PREFIX + key));
    }

    @Override
    public void putExact(String key, CachedAnswer answer, Duration ttl) {
        redis.opsForValue().set(EXACT_PREFIX + key, write(answer), ttl);
    }

    @Override
    public Optional<SemanticMatch> findSimilar(String scope, float[] vector, double minSimilarity) {
        ensureIndex();
        List<?> reply = redis.execute((RedisCallback<List<?>>) connection -> connection.scriptingCommands()
                .eval(SEARCH_SCRIPT, ReturnType.MULTI, 0, bytes(INDEX), bytes(scope), Vectors.toBytes(vector)));
        if (reply == null || reply.size() < 2) {
            return Optional.empty();
        }
        double similarity = 1.0 - Double.parseDouble(text(reply.get(1)));
        if (similarity < minSimilarity) {
            return Optional.empty();
        }
        return read(text(reply.get(0))).map(answer -> new SemanticMatch(answer, similarity));
    }

    @Override
    public void putSemantic(String scope, float[] vector, CachedAnswer answer, Duration ttl) {
        ensureIndex();
        byte[] key = bytes(SEMANTIC_PREFIX + UUID.randomUUID());
        Map<byte[], byte[]> fields = Map.of(
                bytes("scope"), bytes(scope),
                bytes("vector"), Vectors.toBytes(vector),
                bytes("answer"), bytes(write(answer)));
        // One round trip; a hash with no TTL would never leave the cache, so both or neither.
        redis.executePipelined((RedisCallback<Object>) connection -> {
            connection.hashCommands().hMSet(key, fields);
            connection.keyCommands().pExpire(key, ttl.toMillis());
            return null;
        });
    }

    /**
     * Creates the search index on first use. Every instance may try; Redis answers "Index
     * already exists" to all but the first, which is fine. Not at startup, so a gateway can
     * start while Redis is down.
     *
     * <p>FLAT (exact, brute force over the scope's entries) rather than HNSW (approximate): a
     * cache scope holds thousands of entries, not millions, and exact search means production
     * finds the same neighbour the threshold evaluation found.
     */
    void ensureIndex() {
        if (indexReady) {
            return;
        }
        try {
            redis.execute((RedisCallback<Object>) connection -> connection.execute("FT.CREATE",
                    bytes(INDEX), bytes("ON"), bytes("HASH"), bytes("PREFIX"), bytes("1"), bytes(SEMANTIC_PREFIX),
                    bytes("SCHEMA"), bytes("scope"), bytes("TAG"),
                    bytes("vector"), bytes("VECTOR"), bytes("FLAT"), bytes("6"),
                    bytes("TYPE"), bytes("FLOAT32"), bytes("DIM"), bytes(String.valueOf(dimensions)),
                    bytes("DISTANCE_METRIC"), bytes("COSINE")));
        } catch (DataAccessException e) {
            if (!String.valueOf(rootMessage(e)).contains("already exists")) {
                throw e;
            }
        }
        indexReady = true;
    }

    private Optional<CachedAnswer> read(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, CachedAnswer.class));
        } catch (JsonProcessingException e) {
            return Optional.empty(); // unreadable entry (e.g. older format): treat as a miss
        }
    }

    private String write(CachedAnswer answer) {
        try {
            return json.writeValueAsString(answer);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize cached answer", e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Object value) {
        return value instanceof byte[] raw ? new String(raw, StandardCharsets.UTF_8) : String.valueOf(value);
    }

    private static byte[] load(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Missing " + path, e);
        }
    }
}
