package dev.llmgateway.gateway.usage;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Puts the usage interceptor at the front of the chain. Off with {@code gateway.usage.enabled=false}. */
@Configuration
@ConditionalOnProperty(prefix = "gateway.usage", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UsageConfig {

    /** Replaced by any other {@link UsageSink} bean (tests, and the Kafka sink). */
    @Bean
    @ConditionalOnMissingBean(UsageSink.class)
    UsageSink loggingUsageSink() {
        return new LoggingUsageSink();
    }

    @Bean
    UsageInterceptor usageInterceptor(UsageSink sink) {
        return new UsageInterceptor(sink, Clock.systemUTC());
    }
}
