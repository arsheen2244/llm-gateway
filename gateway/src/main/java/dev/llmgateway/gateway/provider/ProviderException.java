package dev.llmgateway.gateway.provider;

/**
 * Any failure while talking to a provider, in a provider-neutral form.
 * Adapters translate their HTTP client's exceptions into this, so the rest of
 * the gateway never sees HTTP-client details.
 */
public class ProviderException extends RuntimeException {

    public enum Reason {
        /** The provider answered with an HTTP error status. */
        ERROR_RESPONSE,
        /** No response within the configured timeout. */
        TIMEOUT,
        /** Network failure before a usable response (refused, reset, DNS...). */
        IO_ERROR,
        /** The stream broke after it had started. */
        STREAM_INTERRUPTED,
        /** The provider answered, but not with something we could parse. */
        INVALID_RESPONSE,
        /** The gateway has no API key for this provider. */
        NOT_CONFIGURED
    }

    private final String provider;
    private final Reason reason;
    private final int upstreamStatus;

    public ProviderException(String provider, Reason reason, int upstreamStatus, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.reason = reason;
        this.upstreamStatus = upstreamStatus;
    }

    public static ProviderException errorResponse(String provider, int status, String upstreamMessage) {
        return new ProviderException(provider, Reason.ERROR_RESPONSE, status, upstreamMessage, null);
    }

    public static ProviderException of(String provider, Reason reason, String message, Throwable cause) {
        return new ProviderException(provider, reason, 0, message, cause);
    }

    public String provider() {
        return provider;
    }

    public Reason reason() {
        return reason;
    }

    /** HTTP status from the provider, or 0 when there was no HTTP response. */
    public int upstreamStatus() {
        return upstreamStatus;
    }

    /**
     * Whether trying again (or trying another provider) could succeed. Nothing
     * uses this yet: it is the seam for week 3's retries and failover.
     */
    public boolean isRetryable() {
        return switch (reason) {
            case ERROR_RESPONSE -> upstreamStatus == 429 || upstreamStatus >= 500;
            case TIMEOUT, IO_ERROR -> true;
            case STREAM_INTERRUPTED, INVALID_RESPONSE, NOT_CONFIGURED -> false;
        };
    }
}
