package dev.llmgateway.gateway.usage;

/** How a request ended, for usage accounting. */
public enum UsageStatus {
    /** An answer was delivered (from a provider or the cache). */
    SUCCESS,
    /** Refused by the rate limiter before anything else ran. */
    RATE_LIMITED,
    /** Every provider failed, or the stream broke after it started. */
    PROVIDER_ERROR,
    /** The client went away mid-stream. */
    CLIENT_DISCONNECTED,
    /** Anything else (a bug or an unexpected failure). */
    ERROR
}
