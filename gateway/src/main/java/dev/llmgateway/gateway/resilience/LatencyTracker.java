package dev.llmgateway.gateway.resilience;

import java.time.Duration;
import java.util.Arrays;

/**
 * Recent successful call latencies for one provider + model, used to pick the hedge delay
 * (owner's choice: observed p95 with a floor; design note 30).
 *
 * <p>A ring buffer of the last {@code capacity} samples: fixed memory, and old samples are
 * overwritten, so the estimate follows the provider as it speeds up or slows down. Computing
 * a percentile sorts a copy (200 numbers: microseconds), which is simpler and exact compared
 * with a streaming estimator.
 */
public final class LatencyTracker {

    private final long[] samples;
    private int count;
    private int next;

    public LatencyTracker(int capacity) {
        this.samples = new long[capacity];
    }

    public synchronized void record(Duration latency) {
        samples[next] = latency.toMillis();
        next = (next + 1) % samples.length;
        count = Math.min(count + 1, samples.length);
    }

    public synchronized int count() {
        return count;
    }

    /**
     * The {@code fraction} percentile (e.g. 0.95) of the stored samples: the smallest
     * latency that at least that fraction of samples are at or below.
     */
    public synchronized Duration percentile(double fraction) {
        if (count == 0) {
            throw new IllegalStateException("no samples yet");
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(fraction * count) - 1);
        return Duration.ofMillis(sorted[index]);
    }
}
