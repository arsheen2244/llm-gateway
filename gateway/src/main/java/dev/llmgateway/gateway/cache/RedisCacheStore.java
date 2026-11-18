package dev.llmgateway.gateway.cache;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Cache in Redis, shared by every gateway instance. Each exact entry is one JSON string under
 * {@code cache:exact:<key>} with a Redis TTL, so expiry needs no cleanup job.
 */
public class RedisCacheStore implements CacheStore {

    static final String EXACT_PREFIX = "cache:exact:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisCacheStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public Optional<CachedAnswer> getExact(String key) {
        String value = redis.opsForValue().get(EXACT_PREFIX + key);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, CachedAnswer.class));
        } catch (JsonProcessingException e) {
            return Optional.empty(); // unreadable entry (e.g. older format): treat as a miss
        }
    }

    @Override
    public void putExact(String key, CachedAnswer answer, Duration ttl) {
        try {
            redis.opsForValue().set(EXACT_PREFIX + key, json.writeValueAsString(answer), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize cached answer", e);
        }
    }
}
