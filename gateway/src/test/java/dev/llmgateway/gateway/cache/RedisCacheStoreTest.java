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
        store = new RedisCacheStore(redis, new ObjectMapper(), FakeEmbedder.DIMENSIONS);
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

    // ---- semantic layer: Redis vector search (design note 35) ----

    @Test
    void nearestPromptInTheScopeIsFoundWithItsSimilarity() {
        String scope = scope();
        store.putSemantic(scope, vector(1.0), new CachedAnswer("close", "stop", "m", 1, 1), Duration.ofSeconds(60));
        store.putSemantic(scope, vector(0.5), new CachedAnswer("far", "stop", "m", 1, 1), Duration.ofSeconds(60));

        var match = store.findSimilar(scope, vector(0.96), 0.9);

        assertThat(match).hasValueSatisfying(m -> {
            assertThat(m.answer().content()).isEqualTo("close");
            assertThat(m.similarity()).isCloseTo(0.96, org.assertj.core.data.Offset.offset(0.001));
        });
    }

    @Test
    void belowTheThresholdIsAMiss() {
        String scope = scope();
        store.putSemantic(scope, vector(1.0), new CachedAnswer("a", "stop", "m", 1, 1), Duration.ofSeconds(60));

        assertThat(store.findSimilar(scope, vector(0.8), 0.9)).isEmpty();
    }

    @Test
    void otherScopesAreNeverSearched() {
        store.putSemantic(scope(), vector(1.0), new CachedAnswer("a", "stop", "m", 1, 1), Duration.ofSeconds(60));

        assertThat(store.findSimilar(scope(), vector(1.0), 0.5)).isEmpty();
    }

    @Test
    void emptyScopeIsAMiss() {
        assertThat(store.findSimilar(scope(), vector(1.0), 0.0)).isEmpty();
    }

    @Test
    void semanticEntriesExpire() throws InterruptedException {
        String scope = scope();
        store.putSemantic(scope, vector(1.0), new CachedAnswer("a", "stop", "m", 1, 1), Duration.ofMillis(200));
        assertThat(store.findSimilar(scope, vector(1.0), 0.9)).isPresent();

        Thread.sleep(400);

        assertThat(store.findSimilar(scope, vector(1.0), 0.9)).isEmpty();
    }

    @Test
    void creatingTheIndexTwiceIsFine() {
        new RedisCacheStore(redis, new ObjectMapper(), FakeEmbedder.DIMENSIONS).ensureIndex();
        new RedisCacheStore(redis, new ObjectMapper(), FakeEmbedder.DIMENSIONS).ensureIndex();
    }

    /** A hex scope, like CacheKeys.scope produces, unique per test. */
    private static String scope() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** A unit vector at the given cosine similarity to vector(1.0). */
    private static float[] vector(double similarity) {
        float[] vector = new float[FakeEmbedder.DIMENSIONS];
        vector[0] = (float) similarity;
        vector[1] = (float) Math.sqrt(1 - similarity * similarity);
        return vector;
    }
}
