package dev.llmgateway.gateway.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.llmgateway.mock.MockProviderApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts real mock providers inside the test JVM, on random ports. Each named instance starts
 * once and is shared for the rest of the run; they stop when the JVM exits (Spring registers a
 * shutdown hook).
 *
 * <p>Settings are passed as command-line arguments, which outrank any application.yml on the
 * classpath (the gateway's is visible here too).
 */
public final class MockProviderServer {

    private static final Map<String, ConfigurableApplicationContext> INSTANCES = new ConcurrentHashMap<>();

    private MockProviderServer() {
    }

    /** The default, always-healthy mock used by most tests. */
    public static String baseUrl() {
        return instance("default");
    }

    /**
     * A separately configured mock, e.g. {@code instance("down", "--mock.error-rate=1.0")}.
     * The settings apply only the first time a name is used.
     */
    public static synchronized String instance(String name, String... mockSettings) {
        ConfigurableApplicationContext context = INSTANCES.computeIfAbsent(name, n -> {
            List<String> args = new ArrayList<>(List.of(
                    "--server.port=0",
                    "--spring.application.name=mock-provider-" + n,
                    "--mock.latency-ms=0",
                    "--mock.chunk-delay-ms=0",
                    "--mock.chunk-count=3"));
            args.addAll(List.of(mockSettings));
            return new SpringApplicationBuilder(MockProviderApplication.class)
                    .properties(MockProviderApplication.defaultProperties())
                    .run(args.toArray(String[]::new));
        });
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return "http://localhost:" + port;
    }
}
