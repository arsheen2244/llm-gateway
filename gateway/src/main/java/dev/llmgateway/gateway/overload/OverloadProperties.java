package dev.llmgateway.gateway.overload;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code gateway.overload} (design note 45).
 *
 * @param enabled     turn load shedding off entirely
 * @param maxInFlight most {@code /v1/} requests one instance handles at once; past it, new ones
 *                    get an immediate 503 instead of piling up in the heap
 * @param retryAfter  what the 503's {@code Retry-After} header tells clients to wait
 */
@Validated
@ConfigurationProperties(prefix = "gateway.overload")
public record OverloadProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") @Min(1) int maxInFlight,
        @DefaultValue("1s") Duration retryAfter) {
}
