package dev.llmgateway.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A usage event as read from Kafka: the consumer's view of {@code contracts/usage-event.json}.
 * Deliberately not shared code with the gateway: each side is deployed on its own, and the
 * contract file, tested from both sides, is what they agree on (design note 39).
 */
public record UsageEvent(
        UUID eventId,
        String clientId,
        String model,
        String provider,
        String upstreamModel,
        Integer promptTokens,
        Integer completionTokens,
        Long latencyMs,
        String cache,
        Status status,
        Boolean stream,
        Instant occurredAt) {

    public enum Status { SUCCESS, RATE_LIMITED, PROVIDER_ERROR, CLIENT_DISCONNECTED, ERROR }

    /** Rejects events missing a field every event must have, or with impossible numbers. */
    public UsageEvent {
        require(eventId != null, "eventId");
        require(clientId != null && !clientId.isBlank(), "clientId");
        require(model != null, "model");
        require(promptTokens != null && promptTokens >= 0, "promptTokens");
        require(completionTokens != null && completionTokens >= 0, "completionTokens");
        require(latencyMs != null, "latencyMs");
        require(cache != null, "cache");
        require(status != null, "status");
        require(stream != null, "stream");
        require(occurredAt != null, "occurredAt");
    }

    public boolean cacheHit() {
        return cache.equals("exact") || cache.equals("semantic");
    }

    /** True if a provider spent tokens on this request, so it should cost something. */
    public boolean billable() {
        return !cacheHit() && (promptTokens > 0 || completionTokens > 0);
    }

    /** Daily totals use UTC dates. */
    public LocalDate day() {
        return occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static void require(boolean ok, String field) {
        if (!ok) {
            throw new IllegalArgumentException("Missing or invalid field: " + field);
        }
    }
}
