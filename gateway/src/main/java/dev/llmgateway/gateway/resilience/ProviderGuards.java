package dev.llmgateway.gateway.resilience;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One circuit breaker and one retry budget per provider instance, created on first use.
 * Per instance, not per model: an outage hits everything served by that endpoint.
 */
@Component
public class ProviderGuards {

    /** What protects one provider. */
    public record Guard(CircuitBreaker breaker, RetryBudget budget) {
    }

    private final ResilienceProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, Guard> guards = new ConcurrentHashMap<>();

    @Autowired
    public ProviderGuards(ResilienceProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public ProviderGuards(ResilienceProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Guard forProvider(String providerName) {
        return guards.computeIfAbsent(providerName, name -> new Guard(
                new SlidingWindowCircuitBreaker(properties.breaker().toConfig(), clock),
                new RetryBudget(properties.retryBudgetRatio(), properties.retryBudgetMinPerSecond(),
                        properties.retryBudgetWindow(), clock)));
    }
}
