package dev.llmgateway.usage;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * What happens when an event can't be recorded (design note 39):
 * <ul>
 *   <li>The event itself is bad (not valid, or PostgreSQL rejects its values): no retry can
 *       help, so it goes to the dead-letter topic and the partition moves on.</li>
 *   <li>Anything else (PostgreSQL down, timeouts, ...): keep trying the same event. Skipping it
 *       would lose it; waiting only delays, since Kafka keeps the events behind it.</li>
 * </ul>
 */
@Configuration
class KafkaErrorConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorConfig.class);

    @Bean
    DefaultErrorHandler usageErrorHandler(KafkaTemplate<String, String> kafka, UsageConsumerProperties properties) {
        DeadLetterPublishingRecoverer toDeadLetters = new DeadLetterPublishingRecoverer(kafka, (record, error) -> {
            log.error("Usage event at {}-{}@{} can't be recorded, moving it to {}: {}", record.topic(),
                    record.partition(), record.offset(), properties.deadLetterTopic(), rootMessage(error));
            return new TopicPartition(properties.deadLetterTopic(), -1); // -1: partition chosen by key
        });
        // If the dead-letter send fails, the recoverer throws and the event is tried again.
        toDeadLetters.setFailIfSendResultIsError(true);
        DefaultErrorHandler handler = new DefaultErrorHandler(toDeadLetters,
                new FixedBackOff(properties.retryInterval().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(InvalidUsageEventException.class, DataIntegrityViolationException.class);
        return handler;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.toString();
    }
}
