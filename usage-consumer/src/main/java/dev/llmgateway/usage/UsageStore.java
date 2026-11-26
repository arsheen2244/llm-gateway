package dev.llmgateway.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records usage events exactly once, whatever Kafka redelivers (design note 39).
 *
 * <p>The event's row and the daily totals change in <b>one transaction</b>, and the totals
 * change only if the row was new. So a redelivered event changes nothing, and a crash at any
 * point leaves either both changes or neither.
 */
@Repository
public class UsageStore {

    private final JdbcClient jdbc;

    public UsageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false if this event was already recorded (and nothing changed) */
    @Transactional
    public boolean record(UsageEvent event) {
        BigDecimal cost = cost(event).orElse(null);
        if (!insertEvent(event, cost)) {
            return false;
        }
        addToDailyTotals(event, cost);
        return true;
    }

    /** Zero if nothing was billed; empty if tokens were billed but no price is known. */
    private Optional<BigDecimal> cost(UsageEvent event) {
        if (!event.billable()) {
            return Optional.of(BigDecimal.ZERO);
        }
        return price(event.provider(), event.upstreamModel(), event.occurredAt())
                .map(price -> price.cost(event.promptTokens(), event.completionTokens()));
    }

    /** The price in force when the event happened. */
    private Optional<ModelPrice> price(String provider, String model, Instant at) {
        return jdbc.sql("""
                        SELECT input_per_million, output_per_million
                          FROM model_prices
                         WHERE provider = :provider AND model = :model AND effective_from <= :at
                         ORDER BY effective_from DESC
                         LIMIT 1""")
                .param("provider", provider)
                .param("model", model)
                .param("at", utc(at))
                .query((row, n) -> new ModelPrice(row.getBigDecimal(1), row.getBigDecimal(2)))
                .optional();
    }

    private boolean insertEvent(UsageEvent event, BigDecimal cost) {
        return jdbc.sql("""
                        INSERT INTO usage_events (event_id, client_id, model, provider, upstream_model,
                            prompt_tokens, completion_tokens, latency_ms, cache, status, stream,
                            occurred_at, cost_usd)
                        VALUES (:eventId, :clientId, :model, :provider, :upstreamModel,
                            :promptTokens, :completionTokens, :latencyMs, :cache, :status, :stream,
                            :occurredAt, :cost)
                        ON CONFLICT (event_id) DO NOTHING""")
                .param("eventId", event.eventId())
                .param("clientId", event.clientId())
                .param("model", event.model())
                .param("provider", event.provider())
                .param("upstreamModel", event.upstreamModel())
                .param("promptTokens", event.promptTokens())
                .param("completionTokens", event.completionTokens())
                .param("latencyMs", event.latencyMs())
                .param("cache", event.cache())
                .param("status", event.status().name())
                .param("stream", event.stream())
                .param("occurredAt", utc(event.occurredAt()))
                .param("cost", cost)
                .update() == 1;
    }

    private void addToDailyTotals(UsageEvent event, BigDecimal cost) {
        boolean success = event.status() == UsageEvent.Status.SUCCESS;
        boolean rateLimited = event.status() == UsageEvent.Status.RATE_LIMITED;
        boolean hit = event.cacheHit();
        jdbc.sql("""
                        INSERT INTO usage_daily AS d (client_id, day, requests, successful_requests,
                            rate_limited_requests, failed_requests, cache_hits, prompt_tokens,
                            completion_tokens, cached_prompt_tokens, cached_completion_tokens,
                            cost_usd, unpriced_requests)
                        VALUES (:clientId, :day, 1, :successful, :rateLimited, :failed, :hits,
                            :prompt, :completion, :cachedPrompt, :cachedCompletion, :cost, :unpriced)
                        ON CONFLICT (client_id, day) DO UPDATE SET
                            requests = d.requests + EXCLUDED.requests,
                            successful_requests = d.successful_requests + EXCLUDED.successful_requests,
                            rate_limited_requests = d.rate_limited_requests + EXCLUDED.rate_limited_requests,
                            failed_requests = d.failed_requests + EXCLUDED.failed_requests,
                            cache_hits = d.cache_hits + EXCLUDED.cache_hits,
                            prompt_tokens = d.prompt_tokens + EXCLUDED.prompt_tokens,
                            completion_tokens = d.completion_tokens + EXCLUDED.completion_tokens,
                            cached_prompt_tokens = d.cached_prompt_tokens + EXCLUDED.cached_prompt_tokens,
                            cached_completion_tokens =
                                d.cached_completion_tokens + EXCLUDED.cached_completion_tokens,
                            cost_usd = d.cost_usd + EXCLUDED.cost_usd,
                            unpriced_requests = d.unpriced_requests + EXCLUDED.unpriced_requests""")
                .param("clientId", event.clientId())
                .param("day", event.day())
                .param("successful", count(success))
                .param("rateLimited", count(rateLimited))
                .param("failed", count(!success && !rateLimited))
                .param("hits", count(hit))
                .param("prompt", hit ? 0 : event.promptTokens())
                .param("completion", hit ? 0 : event.completionTokens())
                .param("cachedPrompt", hit ? event.promptTokens() : 0)
                .param("cachedCompletion", hit ? event.completionTokens() : 0)
                .param("cost", cost == null ? BigDecimal.ZERO : cost)
                .param("unpriced", count(cost == null))
                .update();
    }

    private static int count(boolean happened) {
        return happened ? 1 : 0;
    }

    /** The PostgreSQL driver takes OffsetDateTime for timestamptz, not Instant. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
