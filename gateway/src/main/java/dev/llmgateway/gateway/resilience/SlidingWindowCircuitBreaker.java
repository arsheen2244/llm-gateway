package dev.llmgateway.gateway.resilience;

import java.time.Clock;

/**
 * The circuit breaker state machine: failure rate over a sliding time window.
 *
 * STATUS: stub, to be written by the project owner (week 3, step 4).
 * The behaviour is specified by the {@link CircuitBreaker} javadoc and checked by
 * {@code CircuitBreakerTest}. Read time only from {@code clock} (tests control it), and make
 * every method thread-safe. Design note 28 explains the choices behind the settings.
 *
 * Things you will need to decide (and be ready to explain):
 *   - how to store outcomes so that ones older than the window can be forgotten
 *     (a list of timestamps? fixed time buckets?), and what that costs in memory;
 *   - how to make state changes atomic when many threads report at once
 *     (synchronized? a ReentrantLock? atomics?). Note 2's rule about virtual threads applies:
 *     never block on I/O while holding a lock, but these methods do no I/O.
 */
public class SlidingWindowCircuitBreaker implements CircuitBreaker {

    private final CircuitBreakerConfig config;
    private final Clock clock;

    public SlidingWindowCircuitBreaker(CircuitBreakerConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    @Override
    public boolean tryAcquirePermission() {
        throw new UnsupportedOperationException("Circuit breaker not implemented yet (week 3, step 4)");
    }

    @Override
    public void onSuccess() {
        throw new UnsupportedOperationException("Circuit breaker not implemented yet (week 3, step 4)");
    }

    @Override
    public void onFailure() {
        throw new UnsupportedOperationException("Circuit breaker not implemented yet (week 3, step 4)");
    }

    @Override
    public void onIgnored() {
        throw new UnsupportedOperationException("Circuit breaker not implemented yet (week 3, step 4)");
    }

    @Override
    public State state() {
        throw new UnsupportedOperationException("Circuit breaker not implemented yet (week 3, step 4)");
    }
}
