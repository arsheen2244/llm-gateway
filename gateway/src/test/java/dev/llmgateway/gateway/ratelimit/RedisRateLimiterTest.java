package dev.llmgateway.gateway.ratelimit;

import java.util.UUID;

import dev.llmgateway.gateway.support.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;

/**
 * Runs the shared contract against the Redis limiter, i.e. against acquire.lua and
 * reconcile.lua. The scripts read Redis's own clock, so time really passes here and
 * time-based results are checked within a tolerance.
 *
 * <p>Skipped when no Redis is available (see {@link RedisTestSupport}).
 */
class RedisRateLimiterTest extends RateLimiterContractTest {

    private RedisRateLimiter limiter;
    private String clientId;

    @BeforeEach
    void setUp() {
        limiter = new RedisRateLimiter(RedisTestSupport.newTemplate());
        clientId = "test-" + UUID.randomUUID(); // fresh windows; no flushing a shared Redis
    }

    @Override
    protected RateLimiter limiter() {
        return limiter;
    }

    @Override
    protected void advance(long ms) throws InterruptedException {
        Thread.sleep(ms + 20); // a little extra so the boundary is surely passed
    }

    @Override
    protected long timingToleranceMs() {
        return 150; // sleeps overshoot and each Redis round trip takes time
    }

    @Override
    protected String clientId() {
        return clientId;
    }
}
