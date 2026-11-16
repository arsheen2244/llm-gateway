package dev.llmgateway.gateway.ratelimit;

import java.time.Duration;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code gateway.rate-limit}.
 *
 * @param enabled                  turn limiting off entirely (e.g. for overhead benchmarks)
 * @param backend                  where windows are kept: shared Redis, or this instance's memory
 * @param window                   sliding window length; one minute, matching the per-minute limits
 * @param defaultCompletionTokens  completion tokens to reserve when the client sets no max_tokens
 * @param instances                how many gateway instances share the limits; while Redis is
 *                                 unreachable each instance enforces 1/instances of every limit
 * @param redisRetryInterval       while Redis is unreachable, how often to try it again
 */
@Validated
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("in-memory") Backend backend,
        @DefaultValue("1m") Duration window,
        @DefaultValue("1000") @Positive int defaultCompletionTokens,
        @DefaultValue("1") @Positive int instances,
        @DefaultValue("5s") Duration redisRetryInterval) {

    public enum Backend { IN_MEMORY, REDIS }
}
