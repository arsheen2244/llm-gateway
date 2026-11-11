package dev.llmgateway.gateway.api;

import dev.llmgateway.gateway.provider.ProviderException;
import org.springframework.stereotype.Component;

/**
 * Decides what the client sees when a provider call fails (design note 9).
 *
 * <p>Principles:
 * <ul>
 *   <li>The client's own mistakes (400, 404, 422 from the provider) pass through
 *       with the provider's message, because the client can fix them.</li>
 *   <li>Auth failures (401, 403) are the gateway's misconfiguration, not the
 *       client's. They become 502, and the provider's message is hidden because
 *       it can contain part of our API key.</li>
 *   <li>429 stays 429, so clients back off.</li>
 *   <li>Everything else is the gateway's upstream failing: 502, 503 or 504.</li>
 * </ul>
 */
@Component
public class ProviderErrorMapper {

    public ApiError map(ProviderException e) {
        String provider = e.provider();
        return switch (e.reason()) {
            case ERROR_RESPONSE -> mapStatus(e);
            case TIMEOUT -> ApiError.of(504, "Provider '" + provider + "' timed out", "upstream_error", "provider_timeout");
            case IO_ERROR, INVALID_RESPONSE ->
                    ApiError.of(502, "Provider '" + provider + "' failed: " + e.getMessage(), "upstream_error", "provider_error");
            case STREAM_INTERRUPTED ->
                    ApiError.of(502, "Provider '" + provider + "' stream was interrupted", "upstream_error", "stream_interrupted");
            case NOT_CONFIGURED -> ApiError.of(503, e.getMessage(), "upstream_error", "provider_not_configured");
        };
    }

    private static ApiError mapStatus(ProviderException e) {
        int status = e.upstreamStatus();
        String provider = e.provider();
        return switch (status) {
            case 400, 404, 422 -> ApiError.of(status, e.getMessage(), "invalid_request_error", "provider_rejected_request");
            case 401, 403 -> ApiError.of(502, "Provider '" + provider + "' rejected the gateway's credentials",
                    "upstream_error", "provider_auth_failed");
            case 429 -> ApiError.of(429, "Provider '" + provider + "' is rate limiting requests",
                    "rate_limit_error", "provider_rate_limited");
            case 503, 529 -> ApiError.of(503, "Provider '" + provider + "' is overloaded or unavailable",
                    "upstream_error", "provider_unavailable");
            default -> ApiError.of(502, "Provider '" + provider + "' returned HTTP " + status,
                    "upstream_error", "provider_error");
        };
    }
}
