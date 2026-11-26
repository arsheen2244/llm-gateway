package dev.llmgateway.gateway.usage.report;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Request counts, tokens and cost over some period.
 *
 * @param promptTokens       tokens processed by providers (cached answers are separate)
 * @param cost               US dollars, of the priced requests; written as decimal text so that
 *                           clients parsing JSON numbers as doubles don't round it
 * @param unpricedRequests   requests that used tokens but whose model has no known price, so
 *                           {@code cost} is incomplete when this is above zero
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UsageTotals(
        long requests,
        long successfulRequests,
        long rateLimitedRequests,
        long failedRequests,
        long cacheHits,
        long promptTokens,
        long completionTokens,
        long cachedPromptTokens,
        long cachedCompletionTokens,
        @JsonProperty("cost_usd") PlainDecimal cost,
        long unpricedRequests) {

    public static final UsageTotals NONE =
            new UsageTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, new PlainDecimal(BigDecimal.ZERO), 0);

    public UsageTotals plus(UsageTotals other) {
        return new UsageTotals(
                requests + other.requests,
                successfulRequests + other.successfulRequests,
                rateLimitedRequests + other.rateLimitedRequests,
                failedRequests + other.failedRequests,
                cacheHits + other.cacheHits,
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                cachedPromptTokens + other.cachedPromptTokens,
                cachedCompletionTokens + other.cachedCompletionTokens,
                new PlainDecimal(cost.value().add(other.cost.value())),
                unpricedRequests + other.unpricedRequests);
    }
}
