package dev.llmgateway.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Takes usage events from Kafka one at a time. The offset is committed after this returns,
 * so after the database commit: a crash in between redelivers the event, and the store
 * ignores it the second time (design note 39).
 */
@Component
public class UsageListener {

    private static final Logger log = LoggerFactory.getLogger(UsageListener.class);

    private final UsageStore store;
    private final ObjectMapper json;

    public UsageListener(UsageStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    @KafkaListener(topics = "${usage.topic}")
    public void onEvent(@Payload(required = false) String value) {
        UsageEvent event = parse(value);
        if (!store.record(event)) {
            log.info("Usage event {} was already recorded; ignored", event.eventId());
        }
    }

    UsageEvent parse(String value) {
        if (value == null) { // a Kafka record with no value
            throw new InvalidUsageEventException("Empty message", null);
        }
        try {
            UsageEvent event = json.readValue(value, UsageEvent.class);
            if (event == null) { // the JSON text "null"
                throw new InvalidUsageEventException("Empty event", null);
            }
            return event;
        } catch (JsonProcessingException e) {
            throw new InvalidUsageEventException("Not a usage event: " + e.getOriginalMessage(), e);
        }
    }
}
