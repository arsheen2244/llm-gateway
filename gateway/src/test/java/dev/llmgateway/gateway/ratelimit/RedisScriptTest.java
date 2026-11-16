package dev.llmgateway.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.llmgateway.gateway.support.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Checks that only make sense for the Redis scripts: what they store, how they clean
 * up, and atomicity across separate connections. Behaviour shared with the in-memory
 * limiter is covered by {@link RedisRateLimiterTest}.
 */
class RedisScriptTest {

    private static final Limits LIMITS = new Limits(3, 100, Duration.ofMillis(1_000));

    private StringRedisTemplate redis;
    private RedisRateLimiter limiter;
    private String client;
    private List<String> keys;

    @BeforeEach
    void setUp() {
        redis = RedisTestSupport.newTemplate();
        limiter = new RedisRateLimiter(redis);
        client = "script-" + UUID.randomUUID();
        keys = RedisRateLimiter.keys(client);
    }

    @Test
    void keysFollowTheContractLayout() {
        assertThat(keys).containsExactly(
                "rl:{" + client + "}:log", "rl:{" + client + "}:tokens", "rl:{" + client + "}:sum");
    }

    @Test
    void admittedRequestIsStoredInAllThreeKeys() {
        Reservation reservation = limiter.tryAcquire(client, LIMITS, 42).reservation();

        assertThat(redis.opsForZSet().score(keys.get(0), reservation.id())).isNotNull();
        assertThat(redis.opsForHash().get(keys.get(1), reservation.id())).isEqualTo("42");
        assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("42");
    }

    @Test
    void rejectedRequestStoresNothing() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(client, LIMITS, 1);
        }
        limiter.tryAcquire(client, LIMITS, 1); // rejected
        limiter.tryAcquire(client, LIMITS, 500); // too large

        assertThat(redis.opsForZSet().zCard(keys.get(0))).isEqualTo(3);
        assertThat(redis.opsForHash().size(keys.get(1))).isEqualTo(3);
        assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("3");
    }

    @Test
    void scoresAreRedisTimeInMilliseconds() {
        long before = redisTimeMillis();
        Reservation reservation = limiter.tryAcquire(client, LIMITS, 1).reservation();
        long after = redisTimeMillis();

        Double score = redis.opsForZSet().score(keys.get(0), reservation.id());
        assertThat(score.longValue()).isBetween(before, after);
    }

    @Test
    void everyKeyExpiresWithinOneWindow() {
        limiter.tryAcquire(client, LIMITS, 5);

        for (String key : keys) {
            Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertThat(ttl).as("TTL of " + key).isBetween(1L, LIMITS.window().toMillis());
        }
    }

    @Test
    void quietClientLeavesNothingBehind() throws InterruptedException {
        limiter.tryAcquire(client, LIMITS, 5);

        Thread.sleep(LIMITS.window().toMillis() + 200);

        assertThat(redis.countExistingKeys(keys)).isZero();
    }

    @Test
    void expiredEntriesAreRemovedFromStorageNotJustIgnored() throws InterruptedException {
        limiter.tryAcquire(client, new Limits(3, 100, Duration.ofMillis(500)), 10);
        Thread.sleep(300);
        limiter.tryAcquire(client, new Limits(3, 100, Duration.ofMillis(500)), 20);
        Thread.sleep(300); // the first entry is now out of its 500 ms window

        limiter.tryAcquire(client, new Limits(3, 100, Duration.ofMillis(500)), 30);

        assertThat(redis.opsForZSet().zCard(keys.get(0))).isEqualTo(2);
        assertThat(redis.opsForHash().size(keys.get(1))).isEqualTo(2);
        assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("50");
    }

    @Test
    void reconcileUpdatesTheEntryAndTheSum() {
        Reservation first = limiter.tryAcquire(client, LIMITS, 40).reservation();
        limiter.tryAcquire(client, LIMITS, 10);

        assertThat(limiter.reconcile(first, 25)).isTrue();

        assertThat(redis.opsForHash().get(keys.get(1), first.id())).isEqualTo("25");
        assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("35");
    }

    @Test
    void reconcileOfAnUnknownReservationChangesNothing() {
        limiter.tryAcquire(client, LIMITS, 40);
        Reservation unknown = new Reservation(client, "no-such-id", 10, LIMITS.window());

        assertThat(limiter.reconcile(unknown, 99)).isFalse();

        assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("40");
        assertThat(redis.opsForHash().size(keys.get(1))).isEqualTo(1);
    }

    @Test
    void separateConnectionsStillAdmitExactlyTheLimit() throws Exception {
        // Two limiters on two connections, like two gateway instances sharing one Redis.
        RedisRateLimiter instanceA = new RedisRateLimiter(RedisTestSupport.newTemplate());
        RedisRateLimiter instanceB = new RedisRateLimiter(RedisTestSupport.newTemplate());
        Limits tenPerWindow = new Limits(10, 1_000_000, Duration.ofSeconds(30));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                RedisRateLimiter instance = i % 2 == 0 ? instanceA : instanceB;
                results.add(pool.submit(() -> {
                    start.await();
                    return instance.tryAcquire(client, tenPerWindow, 1).allowed();
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                admitted += result.get() ? 1 : 0;
            }
            assertThat(admitted).isEqualTo(10);
        }
    }

    private long redisTimeMillis() {
        return redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection ->
                connection.serverCommands().time(java.util.concurrent.TimeUnit.MILLISECONDS));
    }
}
