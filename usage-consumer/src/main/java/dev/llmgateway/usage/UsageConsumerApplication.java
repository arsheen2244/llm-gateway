package dev.llmgateway.usage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Reads usage events from Kafka and records them in PostgreSQL (design note 39). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UsageConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsageConsumerApplication.class, args);
    }
}
