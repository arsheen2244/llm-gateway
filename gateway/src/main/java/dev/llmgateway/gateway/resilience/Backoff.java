package dev.llmgateway.gateway.resilience;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with full jitter (design note 27):
 * <pre>  wait = random(0, min(cap, base x 2^(retry - 1)))</pre>
 * The ceiling doubles with each retry (exponential), and the actual wait is a random point
 * below it (full jitter). Without jitter, every client that failed at the same moment would
 * retry at the same moment too, hitting a recovering provider in synchronized waves.
 */
public final class Backoff {

    private final long baseMs;
    private final long capMs;
    private final DoubleSupplier random;

    public Backoff(Duration base, Duration cap, DoubleSupplier random) {
        this.baseMs = base.toMillis();
        this.capMs = cap.toMillis();
        this.random = random;
    }

    /** How long to wait before retry number {@code retry} (1 = the first retry). */
    public Duration beforeRetry(int retry) {
        int doublings = Math.min(retry - 1, 30); // 2^30 x base is far past any cap; avoids overflow
        long ceiling = Math.min(capMs, baseMs << doublings);
        return Duration.ofMillis((long) (random.getAsDouble() * ceiling));
    }
}
