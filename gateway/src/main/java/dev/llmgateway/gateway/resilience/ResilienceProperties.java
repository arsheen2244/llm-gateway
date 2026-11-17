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
        @Valid @DefaultValue Breaker breaker) {

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
                Duration.ofSeconds(10), new Breaker(0.5, 20, Duration.ofSeconds(30), Duration.ofSeconds(30), 3));
    }
}
