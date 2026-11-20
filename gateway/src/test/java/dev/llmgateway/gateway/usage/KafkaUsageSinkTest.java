package dev.llmgateway.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The sink's queue and retry rules, with Kafka's in-memory MockProducer (no broker needed). */
class KafkaUsageSinkTest {

    /** Like Spring Boot's mapper: times as ISO-8601 text, which the consumer reads. */
    static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private KafkaUsageSink sink;

    @AfterEach
    void close() {
        if (sink != null) {
            sink.close();
        }
    }

    @Test
    void eventIsSentAsJsonKeyedByClient() throws Exception {
        MockProducer<String, String> producer = producer(true);
        sink = new KafkaUsageSink(producer, JSON, "usage-events", 10, Duration.ofSeconds(1)).start();
        UsageEvent event = event("client-a");

        sink.publish(event);

        waitFor(() -> sink.sent() == 1);
        ProducerRecord<String, String> record = producer.history().get(0);
        assertThat(record.topic()).isEqualTo("usage-events");
        assertThat(record.key()).isEqualTo("client-a");
        assertThat(JSON.readTree(record.value()).path("eventId").asText()).isEqualTo(event.eventId());
        assertThat(JSON.readTree(record.value()).path("occurredAt").asText()).isEqualTo("2026-11-20T10:15:30.123Z");
        assertThat(JSON.readValue(record.value(), UsageEvent.class)).isEqualTo(event);
    }

    @Test
    void aSendThatTimesOutIsQueuedAndSentAgain() throws Exception {
        MockProducer<String, String> producer = producer(false);
        sink = new KafkaUsageSink(producer, JSON, "t", 10, Duration.ofSeconds(1)).start();
        UsageEvent event = event("c");

        sink.publish(event);
        waitFor(() -> producer.history().size() == 1);
        producer.errorNext(new TimeoutException("Kafka is down"));

        waitFor(() -> producer.history().size() == 2); // the same event, sent again
        producer.completeNext();

        waitFor(() -> sink.sent() == 1);
        assertThat(producer.history()).extracting(ProducerRecord::value).containsOnly(producer.history().get(0).value());
        assertThat(sink.dropped()).isZero();
    }

    @Test
    void anEventKafkaRejectsForGoodIsDropped() throws Exception {
        MockProducer<String, String> producer = producer(false);
        sink = new KafkaUsageSink(producer, JSON, "t", 10, Duration.ofSeconds(1)).start();

        sink.publish(event("c"));
        waitFor(() -> producer.history().size() == 1);
        producer.errorNext(new RecordTooLargeException("too big"));

        waitFor(() -> sink.dropped() == 1);
        assertThat(producer.history()).hasSize(1);
    }

    @Test
    void aFullQueueDropsAndCountsWithoutEverBlocking() {
        // Not started: nothing drains the queue, as when Kafka has been down for a long time.
        sink = new KafkaUsageSink(producer(true), JSON, "t", 2, Duration.ZERO);

        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            sink.publish(event("c"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(sink.queued()).isEqualTo(2);
        assertThat(sink.dropped()).isEqualTo(3);
        assertThat(elapsedMs).isLessThan(100);
    }

    @Test
    void closeSendsWhatIsStillQueued() {
        MockProducer<String, String> producer = producer(true);
        sink = new KafkaUsageSink(producer, JSON, "t", 10, Duration.ofSeconds(1));
        sink.publish(event("c"));
        sink.publish(event("c"));

        sink.close();

        assertThat(producer.history()).hasSize(2);
        assertThat(sink.sent()).isEqualTo(2);
        assertThat(sink.dropped()).isZero();
        assertThat(producer.closed()).isTrue();
        sink = null;
    }

    @Test
    void eventsStillQueuedAfterTheShutdownTimeoutAreCountedAsDropped() {
        sink = new KafkaUsageSink(producer(true), JSON, "t", 10, Duration.ZERO);
        sink.publish(event("c"));
        sink.publish(event("c"));

        sink.close();

        assertThat(sink.dropped()).isEqualTo(2);
        sink = null;
    }

    @Test
    void afterCloseEventsAreDroppedNotQueued() {
        sink = new KafkaUsageSink(producer(true), JSON, "t", 10, Duration.ZERO);
        sink.close();

        sink.publish(event("c"));

        assertThat(sink.queued()).isZero();
        assertThat(sink.dropped()).isEqualTo(1);
        sink = null;
    }

    // ---- helpers ----

    private static MockProducer<String, String> producer(boolean autoComplete) {
        return new MockProducer<>(autoComplete, new StringSerializer(), new StringSerializer());
    }

    static UsageEvent event(String clientId) {
        return new UsageEvent(UUID.randomUUID().toString(), clientId, "mock-gpt", "mock-openai", 10, 20, 42,
                "miss", UsageStatus.SUCCESS, false, Instant.parse("2026-11-20T10:15:30.123Z"));
    }

    static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within 10 s");
            }
            Thread.sleep(5);
        }
    }
}
