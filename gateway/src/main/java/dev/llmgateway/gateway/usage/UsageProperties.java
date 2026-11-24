package dev.llmgateway.gateway.usage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code gateway.usage} (design notes 37-40).
 *
 * @param enabled record a usage event for every request
 * @param sink    where events go: the log (default, for local runs and tests) or Kafka
 * @param kafka   Kafka settings, used when {@code sink} is {@code kafka}
 * @param report  the database behind {@code GET /v1/usage}
 */
@ConfigurationProperties(prefix = "gateway.usage")
public record UsageProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("log") Sink sink,
        @DefaultValue Kafka kafka,
        @DefaultValue Report report) {

    public enum Sink { LOG, KAFKA }

    /**
     * @param bootstrapServers Kafka brokers, {@code host:port[,host:port]}
     * @param topic            where usage events go
     * @param queueCapacity    events held in memory while Kafka is slow or down; beyond this
     *                         they are dropped and counted (note 38)
     * @param deliveryTimeout  how long the producer keeps trying one send before it fails and
     *                         the event goes back into the queue
     * @param shutdownTimeout  how long shutdown may spend sending what is still queued
     */
    public record Kafka(
            @DefaultValue("localhost:9092") String bootstrapServers,
            @DefaultValue("usage-events") String topic,
            @DefaultValue("100000") int queueCapacity,
            @DefaultValue("2m") Duration deliveryTimeout,
            @DefaultValue("5s") Duration shutdownTimeout) {
    }

    /**
     * @param url      JDBC URL; blank means {@code GET /v1/usage} answers 503
     * @param username a role that can only read {@code usage_daily}
     */
    public record Report(
            @DefaultValue("") String url,
            @DefaultValue("usage_reader") String username,
            @DefaultValue("") String password) {
    }
}
