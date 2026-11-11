package dev.llmgateway.gateway.support;

import dev.llmgateway.mock.MockProviderApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts the real mock provider inside the test JVM, once, on a random port.
 * It shuts down when the JVM exits (Spring registers a shutdown hook).
 *
 * <p>Settings are passed as command-line arguments, which outrank any
 * application.yml on the classpath (the gateway's is visible here too).
 */
public final class MockProviderServer {

    private static ConfigurableApplicationContext context;

    private MockProviderServer() {
    }

    public static synchronized String baseUrl() {
        if (context == null) {
            context = new SpringApplicationBuilder(MockProviderApplication.class)
                    .properties(MockProviderApplication.defaultProperties())
                    .run("--server.port=0",
                            "--spring.application.name=mock-provider",
                            "--mock.latency-ms=0",
                            "--mock.chunk-delay-ms=0",
                            "--mock.chunk-count=3");
        }
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return "http://localhost:" + port;
    }
}
