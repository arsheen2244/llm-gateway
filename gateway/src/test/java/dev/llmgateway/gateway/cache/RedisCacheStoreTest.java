package dev.llmgateway.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.support.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The Redis cache store against a real Redis. Skipped when none is available. */
class RedisCacheStoreTest {

    private StringRedisTemplate redis;
    private RedisCacheStore store;

    @BeforeEach
    void setUp() {
        redis = RedisTestSupport.newTemplate();
        store = new RedisCacheStore(redis, new ObjectMapper());
    }

    @Test
    void storedAnswerComesBackWithItsTtl() {
        String key = UUID.randomUUID().toString();
        CachedAnswer answer = new CachedAnswer("Hello!", "stop", "m", 3, 4);

        store.putExact(key, answer, Duration.ofSeconds(60));

        assertThat(store.getExact(key)).hasValue(answer);
        assertThat(redis.getExpire(RedisCacheStore.EXACT_PREFIX + key, TimeUnit.SECONDS)).isBetween(1L, 60L);
    }

    @Test
    void unknownKeyIsAMiss() {
        assertThat(store.getExact(UUID.randomUUID().toString())).isEmpty();
    }
}
