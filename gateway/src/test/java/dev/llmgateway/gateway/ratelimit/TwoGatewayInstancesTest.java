package dev.llmgateway.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.llmgateway.gateway.GatewayApplication;
import dev.llmgateway.gateway.support.MockProviderServer;
import dev.llmgateway.gateway.support.RedisTestSupport;
import dev.llmgateway.gateway.support.TestKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The week-2 concurrency test: two complete gateway applications share one Redis, and many
 * parallel requests for one client are split between them. The shared limit must hold
 * exactly, as if there were a single gateway.
 *
 * <p>Skipped when no Redis is available (see {@link RedisTestSupport}).
 */
class TwoGatewayInstancesTest {

    private static final int LIMIT = 50;
    private static final int REQUESTS = 200;
    private static final String KEY = "two-instances-key";

    private static final List<ConfigurableApplicationContext> gateways = new ArrayList<>();
    private static final List<Integer> ports = new ArrayList<>();

    @BeforeAll
    static void startTwoGateways() {
        String redisUrl = RedisTestSupport.url(); // skips the class if there is no Redis
        // A fresh client id per run, so a shared REDIS_URL never carries an old window over.
        String clientId = "two-instances-" + UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(GatewayApplication.class).run(
                    "--server.port=0",
                    "--spring.data.redis.url=" + redisUrl,
                    "--gateway.rate-limit.backend=redis",
                    "--gateway.rate-limit.instances=2",
                    "--gateway.providers.mock-openai.base-url=" + MockProviderServer.baseUrl(),
                    "--gateway.providers.openai.api-key=",
                    "--gateway.providers.anthropic.api-key=",
                    "--gateway.auth.clients[0].id=" + clientId,
                    "--gateway.auth.clients[0].key-sha256=" + TestKeys.sha256(KEY),
                    "--gateway.auth.clients[0].requests-per-minute=" + LIMIT,
                    "--gateway.auth.clients[0].tokens-per-minute=100000000");
            gateways.add(context);
            ports.add(((WebServerApplicationContext) context).getWebServer().getPort());
        }
    }

    @AfterAll
    static void stopGateways() {
        gateways.forEach(ConfigurableApplicationContext::close);
    }

    @Test
    void parallelRequestsAcrossTwoInstancesAdmitExactlyTheSharedLimit() throws Exception {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < REQUESTS; i++) {
                int port = ports.get(i % 2); // alternate between the two instances
                statuses.add(pool.submit(() -> {
                    start.await();
                    return send(http, port).statusCode();
                }));
            }
            start.countDown();

            Map<Integer, Integer> counts = new TreeMap<>();
            for (Future<Integer> status : statuses) {
                counts.merge(status.get(), 1, Integer::sum);
            }
            // Map equality ignores order. (containsExactlyEntriesOf compares in iteration
            // order, and Map.of's order is deliberately randomized per JVM run.)
            assertThat(counts).isEqualTo(Map.of(200, LIMIT, 429, REQUESTS - LIMIT));
        }
    }

    private static HttpResponse<String> send(HttpClient http, int port) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + KEY)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"mock-gpt\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
