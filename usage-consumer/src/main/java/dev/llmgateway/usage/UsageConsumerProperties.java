package dev.llmgateway.usage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code usage}.
 *
 * @param topic           where the gateways send usage events
 * @param deadLetterTopic where events that can never be recorded go, for a person to look at
 * @param retryInterval   wait between tries while PostgreSQL is unavailable
 */
@ConfigurationProperties(prefix = "usage")
public record UsageConsumerProperties(
        @DefaultValue("usage-events") String topic,
        @DefaultValue("usage-events-dlt") String deadLetterTopic,
        @DefaultValue("1s") Duration retryInterval) {
}
