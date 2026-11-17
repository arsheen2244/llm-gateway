package dev.llmgateway.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class LatencyTrackerTest {

    @Test
    void p95IsTheValueThat95PercentOfSamplesAreAtOrBelow() {
        LatencyTracker tracker = new LatencyTracker(100);
        for (int ms = 1; ms <= 100; ms++) {
            tracker.record(Duration.ofMillis(ms));
        }

        assertThat(tracker.percentile(0.95)).isEqualTo(Duration.ofMillis(95));
        assertThat(tracker.percentile(0.50)).isEqualTo(Duration.ofMillis(50));
        assertThat(tracker.percentile(1.0)).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void oldSamplesAreOverwrittenSoTheEstimateFollowsTheProvider() {
        LatencyTracker tracker = new LatencyTracker(10);
        for (int i = 0; i < 10; i++) {
            tracker.record(Duration.ofMillis(1_000)); // it used to be slow...
        }
        for (int i = 0; i < 10; i++) {
            tracker.record(Duration.ofMillis(100));   // ...and is fast now
        }

        assertThat(tracker.count()).isEqualTo(10);
        assertThat(tracker.percentile(0.95)).isEqualTo(Duration.ofMillis(100));
    }
}
