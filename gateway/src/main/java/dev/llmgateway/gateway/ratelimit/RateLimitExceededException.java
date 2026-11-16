package dev.llmgateway.gateway.ratelimit;

import java.util.Map;

/** A request was refused by the rate limiter. Becomes a 429 in {@code ApiExceptionHandler}. */
public class RateLimitExceededException extends RuntimeException {

    private final RateLimitDecision decision;
    private final Map<String, String> headers;

    public RateLimitExceededException(String message, RateLimitDecision decision, Map<String, String> headers) {
        super(message);
        this.decision = decision;
        this.headers = Map.copyOf(headers);
    }

    public RateLimitDecision decision() {
        return decision;
    }

    /** {@code x-ratelimit-*} and, when retrying can help, {@code Retry-After}. */
    public Map<String, String> headers() {
        return headers;
    }

    /** OpenAI-style error code for the body. */
    public String code() {
        return decision.outcome() == RateLimitDecision.Outcome.TOO_LARGE
                ? "request_too_large_for_limit" : "rate_limit_exceeded";
    }
}
