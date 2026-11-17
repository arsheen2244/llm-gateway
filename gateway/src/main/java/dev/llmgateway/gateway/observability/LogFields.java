package dev.llmgateway.gateway.observability;

/** MDC keys used across the gateway, in one place so log queries can rely on them. */
public final class LogFields {

    public static final String REQUEST_ID = "requestId";
    /** The authenticated gateway client (never its key). */
    public static final String CLIENT_ID = "clientId";
    /** Which limiter decided: "shared" (Redis) or "local-fallback". */
    public static final String RATE_LIMITER = "rateLimiter";
    public static final String MODEL = "model";
    public static final String PROVIDER = "provider";
    /** How many provider calls this request took (retries and failover included). */
    public static final String ATTEMPTS = "attempts";
    /** "true" when a hedged request was sent (design note 30). */
    public static final String HEDGED = "hedged";
    public static final String STREAM = "stream";
    /** For streams: completed, interrupted, or client_disconnected. */
    public static final String STREAM_OUTCOME = "streamOutcome";

    private LogFields() {
    }
}
