package dev.llmgateway.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import dev.llmgateway.gateway.resilience.CircuitBreaker.State;
import org.junit.jupiter.api.Test;

/**
 * The circuit breaker's contract (design note 28), with the default settings:
 * open at >= 50% failures over the last 30 s with >= 20 calls; open for 30 s; 3 trial calls.
 * Time is a fake clock, so nothing here waits.
 */
class CircuitBreakerTest {

    private final FakeClock clock = new FakeClock();
    private final CircuitBreaker breaker = new SlidingWindowCircuitBreaker(CircuitBreakerConfig.defaults(), clock);

    // ---- CLOSED ----

    @Test
    void startsClosedAndAllowsCalls() {
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.tryAcquirePermission()).isTrue();
    }

    @Test
    void staysClosedBelowTheMinimumNumberOfCallsEvenIfAllFail() {
        record(0, 19);

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void opensWhenTheFailureRateReachesTheThresholdWithEnoughCalls() {
        record(10, 9);
        assertThat(breaker.state()).isEqualTo(State.CLOSED); // 19 calls: not enough yet

        record(0, 1); // 20 calls, 10 failures = 50%

        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    @Test
    void staysClosedJustBelowTheThreshold() {
        record(11, 9); // 45% failures

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void outcomesOlderThanTheWindowAreForgotten() {
        record(0, 19);
        clock.advance(Duration.ofSeconds(31));

        record(0, 1); // only 1 call is in the last 30 s now

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void outcomesInsideTheWindowAllCount() {
        record(0, 10);
        clock.advance(Duration.ofSeconds(20));

        record(0, 10); // all 20 are within the last 30 s

        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    // ---- OPEN ----

    @Test
    void openBreakerRejectsCalls() {
        open();

        assertThat(breaker.tryAcquirePermission()).isFalse();
        clock.advance(Duration.ofMillis(29_999));
        assertThat(breaker.tryAcquirePermission()).isFalse();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    @Test
    void lateResultsWhileOpenAreIgnoredAndDoNotExtendTheOpenPeriod() {
        open();
        clock.advance(Duration.ofSeconds(20));

        breaker.onFailure(); // a call that was allowed before the breaker opened
        breaker.onSuccess();
        clock.advance(Duration.ofSeconds(10)); // 30 s after opening

        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
    }

    // ---- HALF_OPEN ----

    @Test
    void afterTheOpenDurationTheNextCallBecomesTheFirstTrial() {
        open();
        clock.advance(Duration.ofSeconds(30));

        assertThat(breaker.state()).isEqualTo(State.OPEN); // nothing changes until someone asks
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
    }

    @Test
    void halfOpenAllowsExactlyTheConfiguredNumberOfTrialCalls() {
        halfOpen();

        assertThat(breaker.tryAcquirePermission()).isTrue();  // trial 2 (trial 1 was taken by halfOpen())
        assertThat(breaker.tryAcquirePermission()).isTrue();  // trial 3
        assertThat(breaker.tryAcquirePermission()).isFalse(); // no more until results come in
    }

    @Test
    void allTrialsSucceedingClosesTheBreakerWithAFreshWindow() {
        halfOpen();
        breaker.tryAcquirePermission();
        breaker.tryAcquirePermission();

        breaker.onSuccess();
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        breaker.onSuccess();

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        record(0, 19); // the old failures are gone: 19 new ones are not enough
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void anyTrialFailureReopensForAFullOpenDuration() {
        halfOpen();

        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(State.OPEN);
        clock.advance(Duration.ofMillis(29_999));
        assertThat(breaker.tryAcquirePermission()).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
    }

    // ---- ignored outcomes ----

    @Test
    void ignoredOutcomesDoNotCount() {
        for (int i = 0; i < 50; i++) {
            breaker.tryAcquirePermission();
            breaker.onIgnored();
        }
        record(0, 19);

        assertThat(breaker.state()).isEqualTo(State.CLOSED); // 19 counted calls, not 69
    }

    @Test
    void ignoredOutcomeInHalfOpenFreesItsTrialSlot() {
        halfOpen();
        breaker.tryAcquirePermission();
        breaker.tryAcquirePermission();
        assertThat(breaker.tryAcquirePermission()).isFalse();

        breaker.onIgnored(); // e.g. the client's own bad request: says nothing about health

        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
    }

    // ---- concurrency ----

    @Test
    void concurrentCallersInHalfOpenGetExactlyTheTrialSlots() throws Exception {
        open();
        clock.advance(Duration.ofSeconds(30));

        int granted = race(100, breaker::tryAcquirePermission);

        assertThat(granted).isEqualTo(3);
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
    }

    @Test
    void concurrentFailuresAreAllCounted() throws Exception {
        // 19 failures from many threads at once: one fewer than needed, so still closed...
        race(19, () -> {
            breaker.tryAcquirePermission();
            breaker.onFailure();
            return true;
        });
        assertThat(breaker.state()).isEqualTo(State.CLOSED);

        // ...and the 20th opens it. A lost update in the counting would break one of these.
        breaker.tryAcquirePermission();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    // ---- helpers ----

    /** Each recorded call first gets permission, as real callers do. */
    private void record(int successes, int failures) {
        for (int i = 0; i < successes; i++) {
            assertThat(breaker.tryAcquirePermission()).isTrue();
            breaker.onSuccess();
        }
        for (int i = 0; i < failures; i++) {
            assertThat(breaker.tryAcquirePermission()).isTrue();
            breaker.onFailure();
        }
    }

    private void open() {
        record(0, 20);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    /** Opens, waits out the open period, and takes the first trial slot. */
    private void halfOpen() {
        open();
        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
    }

    /** Runs {@code task} on {@code threads} virtual threads released at the same moment; counts true results. */
    private static int race(int threads, java.util.concurrent.Callable<Boolean> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            int trues = 0;
            for (Future<Boolean> result : results) {
                trues += result.get() ? 1 : 0;
            }
            return trues;
        }
    }

    /** A clock that only moves when told to. */
    static final class FakeClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);

        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
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
