package dev.llmgateway.gateway.resilience;

import java.time.Duration;

/**
 * Circuit breaker settings (owner's choice: failure rate over a time window; design note 28).
 *
 * @param failureRateThreshold open when at least this fraction of calls in the window failed
 * @param minimumCalls         ...and the window holds at least this many calls, so a few
 *                             unlucky failures at low traffic can't open it
 * @param window               how far back outcomes count (older ones are forgotten)
 * @param openDuration         how long it stays open before trying again
 * @param halfOpenTrialCalls   trial calls allowed in HALF_OPEN; all must succeed to close
 */
public record CircuitBreakerConfig(
        double failureRateThreshold,
        int minimumCalls,
        Duration window,
        Duration openDuration,
        int halfOpenTrialCalls) {

    /** 50% failures, at least 20 calls, in the last 30 s; open 30 s; 3 trial calls. */
    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(0.5, 20, Duration.ofSeconds(30), Duration.ofSeconds(30), 3);
    }
}
