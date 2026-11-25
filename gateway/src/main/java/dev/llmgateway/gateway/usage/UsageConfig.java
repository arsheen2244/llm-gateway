package dev.llmgateway.gateway.usage;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.observability.RequestMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Puts the usage interceptor at the front of the chain. Off with {@code gateway.usage.enabled=false}. */
@Configuration
@ConditionalOnProperty(prefix = "gateway.usage", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UsageConfig {

    /**
     * Replaced by any other {@link UsageSink} bean (tests). Spring calls the Kafka sink's
     * {@code close()} on shutdown, after the web server has stopped taking requests (beans are
     * destroyed after lifecycle components stop), so the last requests' events still get sent.
     */
    @Bean
    @ConditionalOnMissingBean(UsageSink.class)
    UsageSink usageSink(UsageProperties properties, ObjectMapper json) {
        return switch (properties.sink()) {
            case LOG -> new LoggingUsageSink();
            case KAFKA -> kafkaSink(properties.kafka(), json);
        };
    }

    /** Each event goes to the sink and, whatever happens there, into the metrics (note 42). */
    @Bean
    UsageInterceptor usageInterceptor(UsageSink sink, MeterRegistry registry) {
        RequestMetrics metrics = new RequestMetrics(registry);
        return new UsageInterceptor(event -> {
            try {
                sink.publish(event);
            } finally {
                metrics.record(event);
            }
        }, Clock.systemUTC());
    }

    @Bean
    MeterBinder usageSinkMetrics(UsageSink sink) {
        return registry -> {
            if (sink instanceof KafkaUsageSink kafkaSink) {
                new UsageSinkMetrics(kafkaSink).bindTo(registry);
            }
        };
    }

    static KafkaUsageSink kafkaSink(UsageProperties.Kafka kafka, ObjectMapper json) {
        KafkaProducer<String, String> producer = new KafkaProducer<>(producerSettings(kafka),
                new StringSerializer(), new StringSerializer());
        return new KafkaUsageSink(producer, json, kafka.topic(), kafka.queueCapacity(), kafka.shutdownTimeout())
                .start();
    }

    /** Design note 38 explains each value. */
    static Map<String, Object> producerSettings(UsageProperties.Kafka kafka) {
        Map<String, Object> settings = new HashMap<>();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
        settings.put(ProducerConfig.CLIENT_ID_CONFIG, "llm-gateway-usage");
        // Durable once acknowledged, and no duplicates from the producer's own retries.
        settings.put(ProducerConfig.ACKS_CONFIG, "all");
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Batch a few milliseconds of events into one request; compress the JSON.
        settings.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        settings.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        // The producer's own buffer (on top of the sink's queue).
        settings.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 32L * 1024 * 1024);
        settings.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) kafka.deliveryTimeout().toMillis());
        // Only the sender thread ever waits on this; requests never call the producer.
        settings.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        return settings;
    }
}
