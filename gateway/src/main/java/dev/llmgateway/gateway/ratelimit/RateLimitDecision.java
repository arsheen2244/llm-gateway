package dev.llmgateway.gateway.ratelimit;

import java.time.Duration;

/**
 * The answer to "may this request go ahead?".
 *
 * @param outcome           allowed, rejected for now, or too large to ever fit
 * @param reservation       set only when allowed; pass it to {@link RateLimiter#reconcile}
 * @param remainingRequests requests still available in the window (after this one, if allowed)
 * @param remainingTokens   tokens still available in the window (after this one, if allowed)
 * @param retryAfter        when rejected: the earliest time this same request would fit.
 *                          Zero when allowed, null when it can never fit
 */
public record RateLimitDecision(
        Outcome outcome,
        Reservation reservation,
        long remainingRequests,
        long remainingTokens,
        Duration retryAfter) {

    public enum Outcome {
        ALLOWED,
        /** Over a limit now; will fit after {@code retryAfter}. */
        REJECTED,
        /** The estimate is bigger than the whole token limit: it can never fit. */
        TOO_LARGE
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOWED;
    }

    static RateLimitDecision allowed(Reservation reservation, long remainingRequests, long remainingTokens) {
        return new RateLimitDecision(Outcome.ALLOWED, reservation, remainingRequests, remainingTokens, Duration.ZERO);
    }

    static RateLimitDecision rejected(long remainingRequests, long remainingTokens, Duration retryAfter) {
        return new RateLimitDecision(Outcome.REJECTED, null, remainingRequests, remainingTokens, retryAfter);
    }

    static RateLimitDecision tooLarge(long remainingRequests, long remainingTokens) {
        return new RateLimitDecision(Outcome.TOO_LARGE, null, remainingRequests, remainingTokens, null);
    }
}
