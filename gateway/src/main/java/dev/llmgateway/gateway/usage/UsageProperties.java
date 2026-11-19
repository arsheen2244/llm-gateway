package dev.llmgateway.gateway.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code gateway.usage} (design note 37).
 *
 * @param enabled record a usage event for every request
 */
@ConfigurationProperties(prefix = "gateway.usage")
public record UsageProperties(@DefaultValue("true") boolean enabled) {
}
