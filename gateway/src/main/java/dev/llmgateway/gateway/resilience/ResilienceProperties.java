package dev.llmgateway.gateway.resilience;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code gateway.resilience} (design notes 27-28).
 *
 * @param maxAttemptsPerProvider  tries per provider before failing over (2 = one retry)
 * @param backoffBase             first retry waits up to this (full jitter)
 * @param backoffCap              no retry waits longer than this
 * @param retryBudgetRatio        retries may add at most this fraction of recent requests...
 * @param retryBudgetMinPerSecond ...plus this many per second, so quiet providers can retry
 * @param retryBudgetWindow       how far back "recent" reaches
 * @param breaker                 circuit breaker settings, one breaker per provider
 * @param hedging                 hedged requests for non-streaming calls (note 30)
 */
@Validated
@ConfigurationProperties(prefix = "gateway.resilience")
public record ResilienceProperties(
        @DefaultValue("2") @Positive int maxAttemptsPerProvider,
        @DefaultValue("100ms") Duration backoffBase,
        @DefaultValue("2s") Duration backoffCap,
        @DefaultValue("0.1") double retryBudgetRatio,
        @DefaultValue("1") int retryBudgetMinPerSecond,
        @DefaultValue("10s") Duration retryBudgetWindow,
        @Valid @DefaultValue Breaker breaker,
        @Valid @DefaultValue Hedging hedging) {

    /**
     * Hedging settings (owner's choice: delay = observed p95 with a floor; note 30).
     *
     * @param enabled      turn hedging on or off
     * @param percentile   hedge after this percentile of recent successful latencies
     * @param floor        never hedge sooner than this
     * @param defaultDelay delay used until enough samples exist
     * @param minSamples   samples needed before the percentile is trusted
     * @param sampleWindow how many recent successful calls are remembered per provider + model
     * @param maxRatio     hedges may be sent for at most this fraction of recent requests
     */
    public record Hedging(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0.95") double percentile,
            @DefaultValue("300ms") Duration floor,
            @DefaultValue("2s") Duration defaultDelay,
            @DefaultValue("20") @Positive int minSamples,
            @DefaultValue("200") @Positive int sampleWindow,
            @DefaultValue("0.05") double maxRatio) {
    }

    /** The owner's breaker settings (note 28). */
    public record Breaker(
            @DefaultValue("0.5") double failureRateThreshold,
            @DefaultValue("20") @Positive int minimumCalls,
            @DefaultValue("30s") Duration window,
            @DefaultValue("30s") Duration openDuration,
            @DefaultValue("3") @Positive int halfOpenTrialCalls) {

        public CircuitBreakerConfig toConfig() {
            return new CircuitBreakerConfig(failureRateThreshold, minimumCalls, window, openDuration,
                    halfOpenTrialCalls);
        }
    }

    /** All defaults, for tests. */
    public static ResilienceProperties defaults() {
        return new ResilienceProperties(2, Duration.ofMillis(100), Duration.ofSeconds(2), 0.1, 1,
                Duration.ofSeconds(10), new Breaker(0.5, 20, Duration.ofSeconds(30), Duration.ofSeconds(30), 3),
                defaultHedging());
    }

    public static Hedging defaultHedging() {
        return new Hedging(true, 0.95, Duration.ofMillis(300), Duration.ofSeconds(2), 20, 200, 0.05);
    }
}
