package dev.llmgateway.gateway.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes each event to the log at DEBUG. The default until a Kafka sink is configured. */
public class LoggingUsageSink implements UsageSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingUsageSink.class);

    @Override
    public void publish(UsageEvent event) {
        log.debug("Usage event {}", event);
    }
}
