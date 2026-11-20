package dev.llmgateway.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The Kafka sink against a real broker, including one that stops answering (design note 38).
 *
 * <p>The broker is <b>paused</b>, not stopped: connections stay open but nothing replies (the
 * "Kafka hangs" case), and unpausing brings the same broker back. Needs Docker; skipped
 * without it (CI has it).
 */
class KafkaUsageSinkBrokerTest {

    private static KafkaContainer kafka;

    @BeforeAll
    static void startKafka() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Needs Docker for a Kafka broker (runs in CI)");
        kafka = new KafkaContainer("apache/kafka:3.9.1");
        kafka.start();
    }

    @AfterAll
    static void stopKafka() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Test
    void eventsPublishedWhileTheBrokerHangsAreDeliveredOnceItIsBack() throws Exception {
        String topic = "usage-" + UUID.randomUUID();
        UsageProperties.Kafka settings = new UsageProperties.Kafka(kafka.getBootstrapServers(), topic, 10_000,
                Duration.ofMinutes(2), Duration.ofSeconds(5));
        Set<String> published = new HashSet<>();

        try (KafkaUsageSink sink = sink(settings); KafkaConsumer<String, String> consumer = consumer(topic)) {
            // 1. Healthy: events arrive.
            publish(sink, 10, published);
            KafkaUsageSinkTest.waitFor(() -> sink.sent() == 10);

            // 2. The broker hangs. Publishing still never waits.
            pause(true);
            long slowestNanos;
            try {
                slowestNanos = publish(sink, 200, published);
                Thread.sleep(3000);
                assertThat(sink.sent()).as("nothing can be acknowledged while paused").isEqualTo(10);
            } finally {
                pause(false);
            }
            assertThat(Duration.ofNanos(slowestNanos)).isLessThan(Duration.ofMillis(50));

            // 3. Back: everything queued is delivered, nothing is lost.
            Set<String> received = new HashSet<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (!received.containsAll(published) && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    received.add(KafkaUsageSinkTest.JSON.readTree(record.value()).path("eventId").asText());
                    assertThat(record.key()).isEqualTo("client-a");
                }
            }
            assertThat(received).containsAll(published);
            assertThat(sink.dropped()).isZero();
        }
    }

    /** Publishes {@code count} events; returns the slowest single publish, in nanoseconds. */
    private static long publish(KafkaUsageSink sink, int count, Set<String> published) {
        long slowest = 0;
        for (int i = 0; i < count; i++) {
            UsageEvent event = KafkaUsageSinkTest.event("client-a");
            long start = System.nanoTime();
            sink.publish(event);
            slowest = Math.max(slowest, System.nanoTime() - start);
            published.add(event.eventId());
        }
        return slowest;
    }

    private static KafkaUsageSink sink(UsageProperties.Kafka settings) {
        KafkaProducer<String, String> producer = new KafkaProducer<>(UsageConfig.producerSettings(settings),
                new StringSerializer(), new StringSerializer());
        return new KafkaUsageSink(producer, KafkaUsageSinkTest.JSON, settings.topic(), settings.queueCapacity(),
                settings.shutdownTimeout()).start();
    }

    private static KafkaConsumer<String, String> consumer(String topic) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private static void pause(boolean paused) {
        var docker = kafka.getDockerClient();
        if (paused) {
            docker.pauseContainerCmd(kafka.getContainerId()).exec();
        } else {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }
    }
}
