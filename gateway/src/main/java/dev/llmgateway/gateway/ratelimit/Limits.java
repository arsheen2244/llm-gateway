package dev.llmgateway.gateway.ratelimit;

import java.time.Duration;

/**
 * One client's limits over a sliding window.
 *
 * @param maxRequests requests admitted per window
 * @param maxTokens   tokens admitted per window (estimated first, reconciled to actual usage)
 * @param window      window length; one minute in production, shorter in tests
 */
public record Limits(int maxRequests, long maxTokens, Duration window) {

    public static Limits perMinute(int requests, long tokens) {
        return new Limits(requests, tokens, Duration.ofMinutes(1));
    }
}
