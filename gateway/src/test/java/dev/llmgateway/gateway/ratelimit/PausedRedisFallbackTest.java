package dev.llmgateway.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis stops answering mid-run, then comes back (design note 22).
 *
 * <p>The container is <b>paused</b>, not stopped: the TCP connection stays open but nothing
 * replies, which is the realistic "Redis hangs" case, and the mapped port survives so
 * unpausing brings the same Redis back. It has its own container (pausing a shared one would
 * break other tests), so it needs Docker; {@code REDIS_URL} alone is not enough.
 */
class PausedRedisFallbackTest {

    private static GenericContainer<?> redis;
    private static StringRedisTemplate template;

    @BeforeAll
    static void startRedis() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Needs Docker to pause a Redis container (runs in CI)");
        redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
        redis.start();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(100)).build());
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.stop();
        }
    }

    @Test
    void outageFallsBackToLocalShareQuicklyAndRecovers() throws Exception {
        String client = "paused-" + UUID.randomUUID();
        Limits limits = new Limits(4, 1_000_000, Duration.ofMinutes(1));
        FallbackRateLimiter limiter = new FallbackRateLimiter(new RedisRateLimiter(template),
                new InMemoryRateLimiter(Clock.systemUTC()), 2, Duration.ofMillis(500), Clock.systemUTC());

        // 1. Healthy: Redis decides, and the entry is stored there.
        RateLimitDecision healthy = limiter.tryAcquire(client, limits, 1);
        assertThat(healthy.allowed()).isTrue();
        assertThat(limiter.degraded()).isFalse();
        assertThat(template.opsForZSet().zCard(RedisRateLimiter.keys(client).get(0))).isEqualTo(1);

        // 2. Redis hangs: the first request pays the 100 ms timeout once, then goes local.
        pause(true);
        try {
            long start = System.nanoTime();
            RateLimitDecision during = limiter.tryAcquire(client, limits, 1);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(during.allowed()).isTrue();
            assertThat(limiter.degraded()).isTrue();
            assertThat(elapsedMs).isLessThan(1_000);

            // Still limited: this instance's share is ceil(4 / 2) = 2, and one is used.
            assertThat(limiter.tryAcquire(client, limits, 1).allowed()).isTrue();
            assertThat(limiter.tryAcquire(client, limits, 1).allowed()).isFalse();
        } finally {
            pause(false);
        }

        // 3. Redis is back: after the retry interval, the next call probes it and switches back.
        Thread.sleep(600);
        RateLimitDecision recovered = limiter.tryAcquire(client, limits, 1);

        assertThat(limiter.degraded()).isFalse();
        assertThat(recovered.allowed()).isTrue();
        assertThat(template.opsForZSet().zCard(RedisRateLimiter.keys(client).get(0))).isEqualTo(2);
    }

    private static void pause(boolean paused) {
        var docker = redis.getDockerClient();
        if (paused) {
            docker.pauseContainerCmd(redis.getContainerId()).exec();
        } else {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }
}
