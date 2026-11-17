package dev.llmgateway.gateway.resilience;

import java.time.Clock;
import java.time.Duration;

/**
 * Limits retries to a fraction of normal traffic, so retries can't multiply load during an
 * outage (design note 27). The same idea as Finagle's and gRPC's retry budgets.
 *
 * <p>Over a sliding window (default 10 s), retries are allowed while
 * <pre>  retries &lt; minPerSecond x windowSeconds + ratio x requests</pre>
 * With ratio 0.1, retries add at most ~10% on top of first attempts, however bad the
 * outage. The per-second floor lets a provider with little traffic still retry at all.
 *
 * <p>Counts are kept in one bucket per second, so memory is fixed and old seconds are
 * forgotten by overwriting their bucket. Synchronized: the methods are tiny and do no I/O.
 */
public final class RetryBudget {

    private final double ratio;
    private final int minPerSecond;
    private final int seconds;
    private final Clock clock;

    private final long[] bucketSecond;
    private final long[] requests;
    private final long[] retries;

    public RetryBudget(double ratio, int minPerSecond, Duration window, Clock clock) {
        this.ratio = ratio;
        this.minPerSecond = minPerSecond;
        this.seconds = (int) Math.max(1, window.toSeconds());
        this.clock = clock;
        this.bucketSecond = new long[seconds];
        this.requests = new long[seconds];
        this.retries = new long[seconds];
        java.util.Arrays.fill(bucketSecond, Long.MIN_VALUE);
    }

    /** A first attempt was made: it earns a fraction of a retry. */
    public synchronized void recordRequest() {
        requests[bucket()]++;
    }

    /** May a retry be sent now? If yes, it is counted. */
    public synchronized boolean tryAcquireRetry() {
        int current = bucket();
        long now = clock.millis() / 1000;
        long recentRequests = 0;
        long recentRetries = 0;
        for (int i = 0; i < seconds; i++) {
            if (bucketSecond[i] > now - seconds) {
                recentRequests += requests[i];
                recentRetries += retries[i];
            }
        }
        double allowed = (double) minPerSecond * seconds + ratio * recentRequests;
        if (recentRetries + 1 <= allowed) {
            retries[current]++;
            return true;
        }
        return false;
    }

    /** The bucket for the current second, emptied first if it last held an older second. */
    private int bucket() {
        long second = clock.millis() / 1000;
        int index = (int) Math.floorMod(second, seconds);
        if (bucketSecond[index] != second) {
            bucketSecond[index] = second;
            requests[index] = 0;
            retries[index] = 0;
        }
        return index;
    }
}
