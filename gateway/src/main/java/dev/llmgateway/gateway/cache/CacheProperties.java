package dev.llmgateway.gateway.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code gateway.cache} (design note 32).
 *
 * @param enabled             turn the cache off entirely
 * @param ttl                 how long an answer stays cached
 * @param maxTemperature      only requests that explicitly set a temperature at or below this
 *                            are cached: higher temperatures ask for varied answers
 * @param semanticEnabled     turn the semantic (meaning-based) layer on or off
 * @param similarityThreshold cosine similarity needed for a semantic hit. Placeholder until
 *                            the evaluation chooses it (note 35)
 */
@Validated
@ConfigurationProperties(prefix = "gateway.cache")
public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1h") Duration ttl,
        @DefaultValue("0.3") double maxTemperature,
        @DefaultValue("true") boolean semanticEnabled,
        @DefaultValue("0.95") double similarityThreshold) {
}
