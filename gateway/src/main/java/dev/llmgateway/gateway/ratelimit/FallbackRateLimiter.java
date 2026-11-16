package dev.llmgateway.gateway.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import dev.llmgateway.gateway.observability.LogFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;

/**
 * Uses the shared (Redis) limiter while it works, and this instance's own limiter while
 * it doesn't (design note 22; the owner chose "fail open, but not unlimited").
 *
 * <ul>
 *   <li><b>Normal:</b> every call goes to the shared limiter.</li>
 *   <li><b>Shared limiter unreachable</b> (a {@link DataAccessException}: connection refused,
 *       or no reply within the Redis timeout): switch to the local limiter, enforcing
 *       {@code ceil(limit / instances)} so the instances together stay near the real limit.</li>
 *   <li><b>While degraded:</b> don't try Redis on every request, which would add the timeout
 *       to each one. Try it again at most once per {@code retryInterval}; the first success
 *       switches back.</li>
 * </ul>
 * Any other exception (for example a script returning the wrong shape) is a bug and is
 * rethrown, never hidden behind the fallback.
 */
public class FallbackRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FallbackRateLimiter.class);

    private final RateLimiter shared;
    private final RateLimiter local;
    private final int instances;
    private final Duration retryInterval;
    private final Clock clock;
    /** Epoch millis before which the shared limiter is not tried; 0 = healthy. */
    private final AtomicLong degradedUntil = new AtomicLong(0);

    public FallbackRateLimiter(RateLimiter shared, RateLimiter local, int instances,
                               Duration retryInterval, Clock clock) {
        this.shared = shared;
        this.local = local;
        this.instances = instances;
        this.retryInterval = retryInterval;
        this.clock = clock;
    }

    @Override
    public RateLimitDecision tryAcquire(String clientId, Limits limits, long estimatedTokens) {
        if (sharedAllowed()) {
            try {
                RateLimitDecision decision = shared.tryAcquire(clientId, limits, estimatedTokens);
                markHealthy();
                MDC.put(LogFields.RATE_LIMITER, "shared");
                return decision;
            } catch (DataAccessException e) {
                markDegraded(e);
            }
        }
        MDC.put(LogFields.RATE_LIMITER, "local-fallback");
        return local.tryAcquire(clientId, localShare(limits), estimatedTokens);
    }

    /**
     * A reservation may come from either limiter. Try the shared one first; if it doesn't
     * know the reservation (or is unreachable), it came from the local one, or can't be
     * corrected any more.
     */
    @Override
    public boolean reconcile(Reservation reservation, long actualTokens) {
        if (sharedAllowed()) {
            try {
                if (shared.reconcile(reservation, actualTokens)) {
                    return true;
                }
            } catch (DataAccessException e) {
                markDegraded(e);
            }
        }
        return local.reconcile(reservation, actualTokens);
    }

    /** True while healthy, and once per retry interval while degraded (a probe). */
    private boolean sharedAllowed() {
        long until = degradedUntil.get();
        if (until == 0) {
            return true;
        }
        long now = clock.millis();
        // Only one caller per interval wins the probe; the rest stay local.
        return now >= until && degradedUntil.compareAndSet(until, now + retryInterval.toMillis());
    }

    private void markHealthy() {
        if (degradedUntil.getAndSet(0) != 0) {
            log.info("Shared rate limiter reachable again; leaving local fallback");
        }
    }

    private void markDegraded(DataAccessException e) {
        long next = clock.millis() + retryInterval.toMillis();
        if (degradedUntil.getAndSet(next) == 0) {
            log.warn("Shared rate limiter unreachable ({}); enforcing 1/{} of each limit locally, "
                    + "retrying every {} ms", e.getClass().getSimpleName(), instances, retryInterval.toMillis());
        }
    }

    /**
     * This instance's share. Rounded up and at least 1: rounding down could give a small
     * limit a share of 0 (a full outage for that client); rounding up over-admits by at most
     * instances - 1 in total.
     */
    Limits localShare(Limits limits) {
        return new Limits(
                (int) ceilDiv(limits.maxRequests(), instances),
                ceilDiv(limits.maxTokens(), instances),
                limits.window());
    }

    private static long ceilDiv(long value, int parts) {
        return Math.max(1, (value + parts - 1) / parts);
    }

    /** For tests and diagnostics. */
    boolean degraded() {
        return degradedUntil.get() != 0;
    }
}
