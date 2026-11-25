package dev.llmgateway.gateway.resilience;

import dev.llmgateway.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Each provider's circuit-breaker state as a number: 0 closed, 1 half-open, 2 open (worse is
 * higher, so the maximum over instances is the worst one). Read when Prometheus asks; reading
 * never changes a breaker.
 */
@Component
public class BreakerMetrics implements MeterBinder {

    private final ProviderGuards guards;
    private final GatewayProperties properties;

    public BreakerMetrics(ProviderGuards guards, GatewayProperties properties) {
        this.guards = guards;
        this.properties = properties;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String provider : properties.providers().keySet()) {
            Gauge.builder("gateway.breaker.state", guards, g -> value(g.forProvider(provider).breaker().state()))
                    .description("Circuit breaker: 0 closed, 1 half-open, 2 open")
                    .tag("provider", provider)
                    .register(registry);
        }
    }

    static int value(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
        };
    }
}
