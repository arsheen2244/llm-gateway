package dev.llmgateway.gateway.usage.report;

import java.time.Clock;

import dev.llmgateway.gateway.usage.UsageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires {@code GET /v1/usage}. Without a database URL it answers 503; nothing else changes. */
@Configuration
public class UsageReportConfig {

    @Bean
    @ConditionalOnMissingBean(UsageReader.class)
    UsageReader usageReader(UsageProperties properties) {
        UsageProperties.Report report = properties.report();
        if (report.url().isBlank()) {
            return (clientId, from, to) -> {
                throw new UsageUnavailableException("No usage database is configured", null);
            };
        }
        return new JdbcUsageReader(report.url(), report.username(), report.password());
    }

    @Bean
    UsageReports usageReports(UsageReader reader) {
        return new UsageReports(reader, Clock.systemUTC());
    }
}
