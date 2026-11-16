package dev.llmgateway.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** Runs the shared contract against the in-memory limiter, with a fake clock. */
class InMemoryRateLimiterTest extends RateLimiterContractTest {

    private final MutableClock clock = new MutableClock();
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);

    @Override
    protected RateLimiter limiter() {
        return limiter;
    }

    @Override
    protected void advance(long ms) {
        clock.advance(ms);
    }

    @Override
    protected long timingToleranceMs() {
        return 0; // the fake clock makes every duration exact
    }

    @Override
    protected String clientId() {
        return "client"; // each test gets a fresh limiter, so one id is enough
    }

    // ---- boundaries that only a fake clock can hit exactly ----

    @Test
    void entryStillCountsOneMillisecondBeforeItsWindowEnds() {
        limiter.tryAcquire("c", new Limits(1, 100, java.time.Duration.ofMillis(1000)), 1);
        clock.advance(999);

        assertThat(limiter.tryAcquire("c", new Limits(1, 100, java.time.Duration.ofMillis(1000)), 1).allowed())
                .isFalse();
    }

    @Test
    void entryLeavesExactlyWhenItsWindowEnds() {
        limiter.tryAcquire("c", new Limits(1, 100, java.time.Duration.ofMillis(1000)), 1);
        clock.advance(1000);

        assertThat(limiter.tryAcquire("c", new Limits(1, 100, java.time.Duration.ofMillis(1000)), 1).allowed())
                .isTrue();
    }

    /** A clock that only moves when told to. */
    static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);

        void advance(long ms) {
            millis.addAndGet(ms);
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
