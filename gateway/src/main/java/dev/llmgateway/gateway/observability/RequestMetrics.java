package dev.llmgateway.gateway.observability;

import java.time.Duration;
import java.util.Locale;

import dev.llmgateway.gateway.cache.CacheHeaders;
import dev.llmgateway.gateway.usage.UsageEvent;
import dev.llmgateway.gateway.usage.UsageStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The request metric behind most of the dashboard (design note 42), made from the same
 * {@link UsageEvent} that accounting uses, so the two can't disagree about what happened.
 *
 * <p>Every tag has a small, fixed set of values. The model is deliberately <b>not</b> a tag: the
 * client chooses it (routes end in {@code *}), so one client could create endless time series.
 */
public class RequestMetrics {

    static final String REQUESTS = "gateway.requests";

    /**
     * Histogram bucket limits: fine where the gateway's own work shows, up to long LLM answers.
     * Buckets rather than ready-made percentiles, because buckets from several instances can be
     * added up and percentiles cannot.
     */
    private static final Duration[] BUCKETS = {
            Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofMillis(5), Duration.ofMillis(10),
            Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(150),
            Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60)};

    private final MeterRegistry registry;

    public RequestMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Prometheus can't see a counter's first increase if the counter didn't exist before it,
        // so the outcomes worth alerting on exist from the start.
        for (boolean stream : new boolean[] {false, true}) {
            timer(UsageStatus.RATE_LIMITED, null, UsageEvent.NO_CACHE_LOOKUP, stream);
            for (String cache : new String[] {CacheHeaders.MISS, CacheHeaders.BYPASS}) {
                timer(UsageStatus.PROVIDER_ERROR, null, cache, stream);
            }
        }
    }

    public void record(UsageEvent event) {
        timer(event.status(), event.provider(), event.cache(), event.stream())
                .record(Duration.ofMillis(event.latencyMs()));
    }

    /** Finds the timer if it exists already; the registry keeps one per combination of tags. */
    private Timer timer(UsageStatus status, String provider, String cache, boolean stream) {
        return Timer.builder(REQUESTS)
                .description("Chat requests that reached the pipeline, by outcome")
                .tag("status", status.name().toLowerCase(Locale.ROOT))
                .tag("provider", provider == null ? "none" : provider)
                .tag("cache", cache)
                .tag("stream", String.valueOf(stream))
                .serviceLevelObjectives(BUCKETS)
                .register(registry);
    }
}
