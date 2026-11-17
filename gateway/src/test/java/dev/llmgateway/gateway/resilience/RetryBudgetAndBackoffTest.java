package dev.llmgateway.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryBudgetAndBackoffTest {

    private final CircuitBreakerTest.FakeClock clock = new CircuitBreakerTest.FakeClock();

    // ---- retry budget: retries < minPerSecond x seconds + ratio x requests ----

    @Test
    void floorAllowsSomeRetriesEvenWithoutTraffic() {
        RetryBudget budget = new RetryBudget(0.1, 1, Duration.ofSeconds(10), clock);

        int granted = 0;
        for (int i = 0; i < 50; i++) {
            granted += budget.tryAcquireRetry() ? 1 : 0;
        }

        assertThat(granted).isEqualTo(10); // 1 per second x 10 s
    }

    @Test
    void retriesAreLimitedToAFractionOfRequests() {
        RetryBudget budget = new RetryBudget(0.1, 0, Duration.ofSeconds(10), clock);
        for (int i = 0; i < 200; i++) {
            budget.recordRequest();
        }

        int granted = 0;
        for (int i = 0; i < 100; i++) {
            granted += budget.tryAcquireRetry() ? 1 : 0;
        }

        assertThat(granted).isEqualTo(20); // 10% of 200
    }

    @Test
    void oldTrafficLeavesTheWindow() {
        RetryBudget budget = new RetryBudget(0.1, 0, Duration.ofSeconds(10), clock);
        for (int i = 0; i < 100; i++) {
            budget.recordRequest();
        }
        assertThat(budget.tryAcquireRetry()).isTrue();

        clock.advance(Duration.ofSeconds(11)); // those 100 requests are now too old

        assertThat(budget.tryAcquireRetry()).isFalse();
    }

    // ---- backoff: random(0, min(cap, base x 2^(retry-1))) ----

    @Test
    void ceilingDoublesPerRetryUpToTheCap() {
        Backoff atCeiling = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(2), () -> 0.999999);

        assertThat(atCeiling.beforeRetry(1).toMillis()).isEqualTo(99);    // just under 100
        assertThat(atCeiling.beforeRetry(2).toMillis()).isEqualTo(199);   // 200
        assertThat(atCeiling.beforeRetry(3).toMillis()).isEqualTo(399);   // 400
        assertThat(atCeiling.beforeRetry(10).toMillis()).isEqualTo(1999); // capped at 2000
        assertThat(atCeiling.beforeRetry(100).toMillis()).isEqualTo(1999); // no overflow
    }

    @Test
    void fullJitterCanWaitAnywhereFromZero() {
        assertThat(new Backoff(Duration.ofMillis(100), Duration.ofSeconds(2), () -> 0.0).beforeRetry(3))
                .isEqualTo(Duration.ZERO);
        assertThat(new Backoff(Duration.ofMillis(100), Duration.ofSeconds(2), () -> 0.5).beforeRetry(3))
                .isEqualTo(Duration.ofMillis(200));
    }
}
