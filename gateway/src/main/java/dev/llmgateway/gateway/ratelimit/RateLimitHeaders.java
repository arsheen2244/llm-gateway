package dev.llmgateway.gateway.ratelimit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the rate-limit response headers. The names are the ones OpenAI uses, so
 * clients and SDKs that already read OpenAI's limits understand ours too.
 */
public final class RateLimitHeaders {

    public static final String LIMIT_REQUESTS = "x-ratelimit-limit-requests";
    public static final String REMAINING_REQUESTS = "x-ratelimit-remaining-requests";
    public static final String LIMIT_TOKENS = "x-ratelimit-limit-tokens";
    public static final String REMAINING_TOKENS = "x-ratelimit-remaining-tokens";
    public static final String RETRY_AFTER = "Retry-After";

    private RateLimitHeaders() {
    }

    /**
     * The four limit/remaining headers, plus {@code Retry-After} when the request was
     * rejected but will fit later. {@code Retry-After} is in whole seconds (the HTTP
     * standard), rounded up so a client that waits exactly that long will fit.
     */
    public static Map<String, String> of(Limits limits, RateLimitDecision decision) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(LIMIT_REQUESTS, String.valueOf(limits.maxRequests()));
        headers.put(REMAINING_REQUESTS, String.valueOf(Math.max(0, decision.remainingRequests())));
        headers.put(LIMIT_TOKENS, String.valueOf(limits.maxTokens()));
        headers.put(REMAINING_TOKENS, String.valueOf(Math.max(0, decision.remainingTokens())));
        if (decision.outcome() == RateLimitDecision.Outcome.REJECTED) {
            long millis = decision.retryAfter().toMillis();
            headers.put(RETRY_AFTER, String.valueOf(Math.max(1, (millis + 999) / 1000)));
        }
        return headers;
    }
}
