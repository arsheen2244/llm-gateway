package dev.llmgateway.gateway.observability;

/** MDC keys used across the gateway, in one place so log queries can rely on them. */
public final class LogFields {

    public static final String REQUEST_ID = "requestId";
    public static final String MODEL = "model";
    public static final String PROVIDER = "provider";
    public static final String STREAM = "stream";
    /** For streams: completed, interrupted, or client_disconnected. */
    public static final String STREAM_OUTCOME = "streamOutcome";

    private LogFields() {
    }
}
