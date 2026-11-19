package dev.llmgateway.gateway.usage;

import java.time.Instant;

/**
 * One finished request, as recorded for usage accounting (design note 37). Sent to Kafka in
 * week 5 step 2 and written to PostgreSQL by the usage consumer.
 *
 * <p>Holds the client's id, never its API key: the key is a secret, and the id already
 * identifies the caller. Holds tokens, not cost: the consumer prices them from its price
 * table, so prices live in one place.
 *
 * @param eventId          unique per request; the consumer uses it to ignore duplicates
 * @param clientId         the calling client (from its API key)
 * @param model            the model the client asked for
 * @param provider         the provider that answered (or the last one tried); null if none
 *                         was called (rate-limited, served from the cache)
 * @param promptTokens     as reported by the provider (or the cached answer); 0 if unknown
 * @param completionTokens as above
 * @param latencyMs        time spent in the pipeline, from admission to the last byte
 * @param cache            the X-Cache outcome: exact, semantic, miss, bypass; "none" if the
 *                         request ended before the cache was checked
 * @param status           how the request ended
 * @param stream           whether the client asked for a stream
 * @param occurredAt       when the request finished (UTC); daily totals are by this date
 */
public record UsageEvent(
        String eventId,
        String clientId,
        String model,
        String provider,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String cache,
        UsageStatus status,
        boolean stream,
        Instant occurredAt) {

    /** Cache value for a request that ended before the cache step ran. */
    public static final String NO_CACHE_LOOKUP = "none";
}
