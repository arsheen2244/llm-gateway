package dev.llmgateway.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class FallbackRateLimiterTest {

    private static final Limits LIMITS = new Limits(10, 1_000, Duration.ofMinutes(1));

    private final InMemoryRateLimiterTest.MutableClock clock = new InMemoryRateLimiterTest.MutableClock();
    private final SwitchableLimiter shared = new SwitchableLimiter(new InMemoryRateLimiter(clock));
    private final InMemoryRateLimiter local = new InMemoryRateLimiter(clock);
    private final FallbackRateLimiter limiter =
            new FallbackRateLimiter(shared, local, 3, Duration.ofSeconds(5), clock);

    @Test
    void healthySharedLimiterDecides() {
        RateLimitDecision decision = limiter.tryAcquire("c", LIMITS, 10);

        assertThat(decision.remainingRequests()).isEqualTo(9); // the full limit, not a share
        assertThat(shared.calls).isEqualTo(1);
        assertThat(limiter.degraded()).isFalse();
    }

    @Test
    void unreachableSharedLimiterFallsBackToThisInstancesShare() {
        shared.down = true;

        RateLimitDecision decision = limiter.tryAcquire("c", LIMITS, 10);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingRequests()).isEqualTo(4 - 1); // ceil(10 / 3) = 4
        assertThat(decision.remainingTokens()).isEqualTo(334 - 10); // ceil(1000 / 3) = 334
        assertThat(limiter.degraded()).isTrue();
    }

    @Test
    void whileDegradedTheSharedLimiterIsNotTriedOnEveryRequest() {
        shared.down = true;
        limiter.tryAcquire("c", LIMITS, 1);
        int callsAfterFailure = shared.calls;

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("c", LIMITS, 1);
        }

        assertThat(shared.calls).isEqualTo(callsAfterFailure);
    }

    @Test
    void afterTheRetryIntervalOneProbeIsSentAndSuccessSwitchesBack() {
        shared.down = true;
        limiter.tryAcquire("c", LIMITS, 1);
        shared.down = false;
        clock.advance(5_000);

        RateLimitDecision decision = limiter.tryAcquire("c", LIMITS, 1);

        assertThat(limiter.degraded()).isFalse();
        assertThat(decision.remainingRequests()).isEqualTo(9); // the shared limiter answered
    }

    @Test
    void failedProbeKeepsUsingTheLocalLimiter() {
        shared.down = true;
        limiter.tryAcquire("c", LIMITS, 1);
        clock.advance(5_000);
        int callsBeforeProbe = shared.calls;

        limiter.tryAcquire("c", LIMITS, 1);
        limiter.tryAcquire("c", LIMITS, 1);

        assertThat(shared.calls).isEqualTo(callsBeforeProbe + 1); // exactly one probe
        assertThat(limiter.degraded()).isTrue();
    }

    @Test
    void localLimitIsEnforcedDuringTheOutage() {
        shared.down = true;
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryAcquire("c", LIMITS, 1).allowed()).isTrue();
        }

        assertThat(limiter.tryAcquire("c", LIMITS, 1).allowed()).isFalse();
    }

    @Test
    void shareIsRoundedUpAndNeverZero() {
        assertThat(limiter.localShare(new Limits(2, 2, LIMITS.window())))
                .isEqualTo(new Limits(1, 1, LIMITS.window()));
        assertThat(limiter.localShare(new Limits(1, 1, LIMITS.window())))
                .isEqualTo(new Limits(1, 1, LIMITS.window()));
    }

    @Test
    void reservationsAreReconciledWhereTheyWereMade() {
        shared.down = true;
        Reservation localReservation = limiter.tryAcquire("c", LIMITS, 300).reservation();
        shared.down = false;
        clock.advance(5_000);

        // Redis is back, but this reservation lives in the local limiter.
        assertThat(limiter.reconcile(localReservation, 10)).isTrue();
        assertThat(local.tryAcquire("c", limiter.localShare(LIMITS), 0).remainingTokens()).isEqualTo(334 - 10);
    }

    @Test
    void reconcileDuringAnOutageNeverThrows() {
        Reservation sharedReservation = limiter.tryAcquire("c", LIMITS, 300).reservation();
        shared.down = true;

        assertThat(limiter.reconcile(sharedReservation, 10)).isFalse();
    }

    @Test
    void bugsAreNotHiddenBehindTheFallback() {
        RateLimiter broken = new RateLimiter() {
            @Override
            public RateLimitDecision tryAcquire(String clientId, Limits limits, long estimatedTokens) {
                throw new IllegalStateException("Rate-limit script returned [1, 2]");
            }

            @Override
            public boolean reconcile(Reservation reservation, long actualTokens) {
                return false;
            }
        };
        FallbackRateLimiter withBug = new FallbackRateLimiter(broken, local, 3, Duration.ofSeconds(5), clock);

        assertThatThrownBy(() -> withBug.tryAcquire("c", LIMITS, 1)).isInstanceOf(IllegalStateException.class);
    }

    /** A limiter that throws like an unreachable Redis while {@code down} is true. */
    private static final class SwitchableLimiter implements RateLimiter {
        private final RateLimiter delegate;
        boolean down;
        int calls;

        SwitchableLimiter(RateLimiter delegate) {
            this.delegate = delegate;
        }

        @Override
        public RateLimitDecision tryAcquire(String clientId, Limits limits, long estimatedTokens) {
            calls++;
            if (down) {
                throw new RedisConnectionFailureException("Unable to connect to Redis");
            }
            return delegate.tryAcquire(clientId, limits, estimatedTokens);
        }

        @Override
        public boolean reconcile(Reservation reservation, long actualTokens) {
            if (down) {
                throw new RedisConnectionFailureException("Unable to connect to Redis");
            }
            return delegate.reconcile(reservation, actualTokens);
        }
    }
}
