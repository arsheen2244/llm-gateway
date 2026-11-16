package dev.llmgateway.gateway.ratelimit;

/**
 * Sliding-window limits on requests and tokens per client (design note 18).
 *
 * <p>Semantics every implementation must follow (checked by {@code RateLimiterContractTest}):
 * <ul>
 *   <li>A request is admitted only if, after admitting it, <b>both</b> the request count
 *       and the token total in the window stay within the limits. The check and the
 *       update happen atomically.</li>
 *   <li>An entry admitted at time {@code t} leaves the window at {@code t + window}
 *       (it counts while {@code admittedAt > now - window}).</li>
 *   <li>Rejected requests are not recorded, so a client that keeps retrying does not
 *       push its own recovery further away.</li>
 *   <li>{@code retryAfter} is the earliest moment the same request would fit, found by
 *       expiring entries oldest-first until both limits fit.</li>
 *   <li>An estimate larger than the whole token limit is {@code TOO_LARGE}: it never fits.</li>
 * </ul>
 */
public interface RateLimiter {

    RateLimitDecision tryAcquire(String clientId, Limits limits, long estimatedTokens);

    /**
     * Replaces the reserved token count with the actual usage. A no-op (returns false)
     * if the entry has already left the window, because it no longer counts anyway.
     * Negative values are treated as zero.
     */
    boolean reconcile(Reservation reservation, long actualTokens);
}
