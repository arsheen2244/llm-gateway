package dev.llmgateway.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.llmgateway.gateway.ratelimit.RateLimitDecision.Outcome;
import org.junit.jupiter.api.Test;

/**
 * The behaviour every {@link RateLimiter} must have. Each implementation extends this
 * class: the in-memory limiter jumps a fake clock, the Redis limiter really waits (its
 * script reads Redis's own clock), so time-based values are checked within
 * {@link #timingToleranceMs()}.
 *
 * <p>All cases use a 1-second window, at most 3 requests and 100 tokens.
 */
public abstract class RateLimiterContractTest {

    protected static final long WINDOW_MS = 1_000;
    protected static final Limits LIMITS = new Limits(3, 100, Duration.ofMillis(WINDOW_MS));

    /** A fresh limiter with empty windows. */
    protected abstract RateLimiter limiter();

    /** Lets {@code ms} milliseconds pass for the limiter. */
    protected abstract void advance(long ms) throws InterruptedException;

    /** How far a measured duration may fall short of the exact value (0 = exact). */
    protected abstract long timingToleranceMs();

    /** A client id unique to the calling test, so tests never share a window. */
    protected abstract String clientId();

    // ---- admitting ----

    @Test
    void firstRequestIsAllowedAndReportsWhatRemains() {
        RateLimitDecision decision = limiter().tryAcquire(clientId(), LIMITS, 10);

        assertThat(decision.outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(decision.remainingRequests()).isEqualTo(2);
        assertThat(decision.remainingTokens()).isEqualTo(90);
        assertThat(decision.retryAfter()).isEqualTo(Duration.ZERO);
        assertThat(decision.reservation()).isNotNull();
        assertThat(decision.reservation().reservedTokens()).isEqualTo(10);
    }

    @Test
    void requestLimitRejectsTheFourthRequest() {
        RateLimiter limiter = limiter();
        String client = clientId();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(client, LIMITS, 1).allowed()).isTrue();
        }

        RateLimitDecision fourth = limiter.tryAcquire(client, LIMITS, 1);

        assertThat(fourth.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(fourth.reservation()).isNull();
        assertThat(fourth.remainingRequests()).isZero();
        assertThat(fourth.retryAfter()).isPositive();
    }

    @Test
    void rejectedRequestsAreNotRecorded() {
        RateLimiter limiter = limiter();
        String client = clientId();
        limiter.tryAcquire(client, LIMITS, 90);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(client, LIMITS, 50).allowed()).isFalse();
        }

        // Five rejections later, the unused capacity is all still there.
        RateLimitDecision next = limiter.tryAcquire(client, LIMITS, 10);

        assertThat(next.allowed()).isTrue();
        assertThat(next.remainingRequests()).isEqualTo(1);
        assertThat(next.remainingTokens()).isZero();
    }

    @Test
    void tokenLimitRejectsEvenWhenRequestsRemain() {
        RateLimiter limiter = limiter();
        String client = clientId();
        limiter.tryAcquire(client, LIMITS, 60);

        RateLimitDecision decision = limiter.tryAcquire(client, LIMITS, 50);

        assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(decision.remainingRequests()).isEqualTo(2);
        assertThat(decision.remainingTokens()).isEqualTo(40);
    }

    @Test
    void exactlyFillingTheTokenLimitIsAllowed() {
        RateLimiter limiter = limiter();
        String client = clientId();
        limiter.tryAcquire(client, LIMITS, 60);

        RateLimitDecision decision = limiter.tryAcquire(client, LIMITS, 40);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingTokens()).isZero();
    }

    @Test
    void estimateLargerThanTheWholeTokenLimitCanNeverFit() {
        RateLimiter limiter = limiter();
        String client = clientId();

        RateLimitDecision decision = limiter.tryAcquire(client, LIMITS, 101);

        assertThat(decision.outcome()).isEqualTo(Outcome.TOO_LARGE);
        assertThat(decision.retryAfter()).isNull();
        // Nothing was recorded.
        assertThat(limiter.tryAcquire(client, LIMITS, 100).allowed()).isTrue();
    }

    @Test
    void clientsHaveSeparateWindows() {
        RateLimiter limiter = limiter();
        String a = clientId() + "-a";
        String b = clientId() + "-b";
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(a, LIMITS, 1);
        }

        assertThat(limiter.tryAcquire(a, LIMITS, 1).allowed()).isFalse();
        assertThat(limiter.tryAcquire(b, LIMITS, 1).allowed()).isTrue();
    }

    // ---- time ----

    @Test
    void capacityReturnsWhenTheWindowHasPassed() throws InterruptedException {
        RateLimiter limiter = limiter();
        String client = clientId();
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(client, LIMITS, 30);
        }
        assertThat(limiter.tryAcquire(client, LIMITS, 30).allowed()).isFalse();

        advance(WINDOW_MS);

        RateLimitDecision decision = limiter.tryAcquire(client, LIMITS, 30);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingRequests()).isEqualTo(2);
        assertThat(decision.remainingTokens()).isEqualTo(70);
    }

    @Test
    void retryAfterIsWhenTheOldestEntryExpiresForTheRequestLimit() throws InterruptedException {
        RateLimiter limiter = limiter();
        String client = clientId();
        limiter.tryAcquire(client, LIMITS, 1);   // t = 0
        advance(300);
        limiter.tryAcquire(client, LIMITS, 1);   // t = 300
        advance(300);
        limiter.tryAcquire(client, LIMITS, 1);   // t = 600

        RateLimitDecision decision = limiter.tryAcquire(client, LIMITS, 1);

        // The entry from t = 0 leaves at t = 1000, which is 400 ms after "now" (t = 600).
        assertAbout(decision.retryAfter(), 400);
    }

    @Test
    void retryAfterWaitsForAsManyExpiriesAsTheTokensNeed() throws InterruptedException {
        RateLimiter limiter = limiter();
        String client = clientId();
        limiter.tryAcquire(client, LIMITS, 50);  // t = 0, leaves at 1000
        advance(300);
        limiter.tryAcquire(client, LIMITS, 40);  // t = 300, leaves at 1300
        advance(300);

        // At t = 600: 90 tokens used. 95 more fit only once BOTH entries have left.
        RateLimitDecision decision = limiter.tryAcquire(client, LIMITS, 95);

        assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
        assertAbout(decision.retryAfter(), 700);
    }

    // ---- reconcile ----

    @Test
    void reconcilingDownReturnsUnusedTokens() {
        RateLimiter limiter = limiter();
        String client = clientId();
        Reservation reservation = limiter.tryAcquire(client, LIMITS, 80).reservation();

        assertThat(limiter.reconcile(reservation, 20)).isTrue();

        RateLimitDecision next = limiter.tryAcquire(client, LIMITS, 70);
        assertThat(next.allowed()).isTrue();
        assertThat(next.remainingTokens()).isEqualTo(10);
    }

    @Test
    void reconcilingUpUsesMoreTokens() {
        RateLimiter limiter = limiter();
        String client = clientId();
        Reservation reservation = limiter.tryAcquire(client, LIMITS, 10).reservation();

        limiter.reconcile(reservation, 90);

        RateLimitDecision next = limiter.tryAcquire(client, LIMITS, 20);
        assertThat(next.allowed()).isFalse();
        assertThat(next.remainingTokens()).isEqualTo(10);
    }

    @Test
    void reconcilingToZeroOrBelowNeverMakesUsageNegative() {
        RateLimiter limiter = limiter();
        String client = clientId();
        Reservation reservation = limiter.tryAcquire(client, LIMITS, 30).reservation();

        limiter.reconcile(reservation, -5);

        RateLimitDecision next = limiter.tryAcquire(client, LIMITS, 100);
        assertThat(next.allowed()).isTrue();
        assertThat(next.remainingTokens()).isZero();
    }

    @Test
    void reconcilingAnExpiredEntryDoesNothing() throws InterruptedException {
        RateLimiter limiter = limiter();
        String client = clientId();
        Reservation reservation = limiter.tryAcquire(client, LIMITS, 30).reservation();
        advance(WINDOW_MS);

        assertThat(limiter.reconcile(reservation, 90)).isFalse();

        RateLimitDecision next = limiter.tryAcquire(client, LIMITS, 100);
        assertThat(next.allowed()).isTrue();
    }

    // ---- concurrency ----

    @Test
    void concurrentRequestsAdmitExactlyTheLimit() throws Exception {
        RateLimiter limiter = limiter();
        String client = clientId();
        Limits tenPerWindow = new Limits(10, 1_000_000, Duration.ofSeconds(30));
        int callers = 100;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await(); // release all callers at once
                    return limiter.tryAcquire(client, tenPerWindow, 1).allowed();
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

    /** Exact for a fake clock; within the tolerance (never above) for a real one. */
    private void assertAbout(Duration actual, long expectedMs) {
        assertThat(actual.toMillis()).isBetween(expectedMs - timingToleranceMs(), expectedMs);
    }
}
