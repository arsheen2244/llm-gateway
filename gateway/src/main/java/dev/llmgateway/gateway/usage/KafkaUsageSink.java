package dev.llmgateway.gateway.usage;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends usage events to Kafka without ever making a request wait (design note 38).
 *
 * <p>{@link #publish} only offers the event to a bounded in-memory queue and returns. One
 * background thread takes events from the queue and hands them to the Kafka producer, which
 * may block (fetching metadata, a full producer buffer) without any request noticing.
 *
 * <p>While Kafka is down, events wait in the queue (and in the producer's own buffer). A send
 * that fails with a retriable error (e.g. it timed out) goes back into the queue to be sent
 * again later. Events are lost, and counted in {@link #dropped()}, only when:
 * <ul>
 *   <li>the queue is full (the outage outlasted the buffer);</li>
 *   <li>Kafka rejects an event for good (a non-retriable error);</li>
 *   <li>the instance shuts down with events still unsent after the shutdown timeout, or
 *       crashes.</li>
 * </ul>
 * A re-sent event may reach Kafka twice (a timed-out send can still have succeeded); the
 * consumer ignores duplicates by event ID (note 39).
 *
 * <p>Each event is keyed by client id, so one client's events stay in order on one partition.
 */
public class KafkaUsageSink implements UsageSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaUsageSink.class);
    private static final long LOG_EVERY_N_DROPS = 1000;

    private final Producer<String, String> producer;
    private final ObjectMapper json;
    private final String topic;
    private final BlockingQueue<UsageEvent> queue;
    private final Duration shutdownTimeout;
    private final Thread sender;
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;

    public KafkaUsageSink(Producer<String, String> producer, ObjectMapper json, String topic, int queueCapacity,
                          Duration shutdownTimeout) {
        this.producer = producer;
        this.json = json;
        this.topic = topic;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.shutdownTimeout = shutdownTimeout;
        this.sender = Thread.ofPlatform().name("usage-kafka-sender").daemon().unstarted(this::sendLoop);
    }

    public KafkaUsageSink start() {
        sender.start();
        return this;
    }

    /** Never blocks: either the event is queued or it is dropped and counted. */
    @Override
    public void publish(UsageEvent event) {
        if (!running) {
            drop(event, "sink closed");
        } else if (!queue.offer(event)) {
            drop(event, "buffer full");
        }
    }

    /** Events Kafka has acknowledged. */
    public long sent() {
        return sent.get();
    }

    /** Events lost: buffer full, rejected by Kafka, or unsent at shutdown. */
    public long dropped() {
        return dropped.get();
    }

    /** Events waiting to be handed to the producer. */
    public int queued() {
        return queue.size();
    }

    private void sendLoop() {
        while (running) {
            try {
                UsageEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    send(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Usage sender: unexpected error: {}", e.toString());
            }
        }
    }

    private void send(UsageEvent event) {
        String value;
        try {
            value = json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            drop(event, "not serializable: " + e.getMessage());
            return;
        }
        try {
            producer.send(new ProducerRecord<>(topic, event.clientId(), value), (metadata, error) -> {
                if (error == null) {
                    sent.incrementAndGet();
                } else {
                    retryOrDrop(event, error);
                }
            });
        } catch (RuntimeException e) {
            retryOrDrop(event, e);
        }
    }

    private void retryOrDrop(UsageEvent event, Exception error) {
        if (error instanceof RetriableException && running && queue.offer(event)) {
            return; // sent again later
        }
        drop(event, error.toString());
    }

    private void drop(UsageEvent event, String why) {
        long total = dropped.incrementAndGet();
        if (total == 1 || total % LOG_EVERY_N_DROPS == 0) {
            log.warn("Dropped usage event {} ({}); {} dropped so far", event.eventId(), why, total);
        }
    }

    /** Sends what is still queued, for up to the shutdown timeout, then closes the producer. */
    @Override
    public void close() {
        running = false;
        try {
            sender.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        UsageEvent event;
        while (System.nanoTime() < deadline && (event = queue.poll()) != null) {
            send(event);
        }
        long remaining = Math.max(0, deadline - System.nanoTime());
        producer.close(Duration.ofNanos(remaining));
        int unsent = queue.size();
        queue.clear();
        if (unsent > 0) {
            dropped.addAndGet(unsent);
            log.warn("Shut down with {} usage events unsent", unsent);
        }
        log.info("Usage sink closed: {} sent, {} dropped", sent.get(), dropped.get());
    }
}
