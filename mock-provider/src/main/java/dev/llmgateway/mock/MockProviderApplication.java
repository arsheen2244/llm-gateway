package dev.llmgateway.mock;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A fake LLM provider. It answers in the same wire formats as OpenAI and Anthropic,
 * so the gateway's real adapters can be tested without real API keys or costs.
 *
 * <p>This module deliberately has no application.yml. Its integration tests run
 * in the same JVM as the gateway, and two application.yml files on one classpath
 * would clash. Defaults live in code ({@link #defaultProperties()}), and everything
 * can be overridden with environment variables.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MockProviderApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MockProviderApplication.class);
        app.setDefaultProperties(defaultProperties());
        app.run(args);
    }

    /** Defaults shared by {@link #main} and by tests that start the mock in-process. */
    public static Map<String, Object> defaultProperties() {
        return Map.of(
                "spring.application.name", "mock-provider",
                "server.port", "8081",
                "spring.threads.virtual.enabled", "true");
    }
}
