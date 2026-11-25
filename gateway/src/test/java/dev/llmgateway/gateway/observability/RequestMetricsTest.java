package dev.llmgateway.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import dev.llmgateway.gateway.usage.UsageEvent;
import dev.llmgateway.gateway.usage.UsageStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class RequestMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RequestMetrics metrics = new RequestMetrics(registry);

    @Test
    void recordsEachRequestUnderItsOutcome() {
        metrics.record(event("gpt-a", "openai", "miss", UsageStatus.SUCCESS, 120));
        metrics.record(event("gpt-b", "openai", "miss", UsageStatus.SUCCESS, 80));
        metrics.record(event("gpt-a", null, "exact", UsageStatus.SUCCESS, 1));

        var timer = registry.get("gateway.requests").tags("status", "success", "provider", "openai",
                "cache", "miss", "stream", "false").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(200);
        assertThat(registry.get("gateway.requests").tags("provider", "none", "cache", "exact").timer().count())
                .isEqualTo(1);
    }

    @Test
    void modelNamesChosenByClientsCreateNoNewTimeSeries() {
        metrics.record(event("gpt-0", "openai", "miss", UsageStatus.SUCCESS, 1));
        int before = registry.getMeters().size();

        for (int i = 1; i < 1000; i++) {
            metrics.record(event("gpt-" + i, "openai", "miss", UsageStatus.SUCCESS, 1));
        }

        assertThat(registry.getMeters()).hasSize(before);
    }

    @Test
    void rejectionsAndErrorsExistAtZeroBeforeTheFirstOne() {
        assertThat(registry.get("gateway.requests").tag("status", "rate_limited").timers()).isNotEmpty()
                .allSatisfy(timer -> assertThat(timer.count()).isZero());
        assertThat(registry.get("gateway.requests").tag("status", "provider_error").timers()).isNotEmpty();
    }

    private static UsageEvent event(String model, String provider, String cache, UsageStatus status, long latencyMs) {
        return new UsageEvent("id", "client", model, provider, model, 1, 1, latencyMs, cache, status, false,
                Instant.EPOCH);
    }
}
