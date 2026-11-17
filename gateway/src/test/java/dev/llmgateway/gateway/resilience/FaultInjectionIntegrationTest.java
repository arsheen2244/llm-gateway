package dev.llmgateway.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import dev.llmgateway.gateway.support.MockProviderServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Fault injection through the real gateway, with several separately configured mock
 * providers (design note 27):
 * <ul>
 *   <li>{@code down}, {@code down2}: every request answers 500</li>
 *   <li>{@code up}: healthy</li>
 *   <li>{@code flappy}: answers 503 for the first 500 ms of every second</li>
 *   <li>{@code broken}: every stream drops after its first content chunk</li>
 * </ul>
 * Breakers here open after 4 calls at 50% failures and stay open for 2 s, so the tests are quick.
 */
class FaultInjectionIntegrationTest extends GatewayIntegrationTest {

    private static final String[][] MOCKS = {
            {"down", "--mock.error-rate=1.0"},
            {"down2", "--mock.error-rate=1.0"},
            {"up"},
            {"flappy", "--mock.flap-period-ms=1000", "--mock.flap-down-ms=500"},
            {"broken", "--mock.mid-stream-failure-rate=1.0"},
    };

    @DynamicPropertySource
    static void providersAndRoutes(DynamicPropertyRegistry registry) {
        for (String[] mock : MOCKS) {
            String name = mock[0];
            String[] settings = java.util.Arrays.copyOfRange(mock, 1, mock.length);
            registry.add("gateway.providers." + name + ".type", () -> "openai");
            registry.add("gateway.providers." + name + ".base-url", () -> MockProviderServer.instance(name, settings));
            registry.add("gateway.providers." + name + ".api-key", () -> "mock-key");
        }
        // Lists are replaced, not merged, so these are this test's only routes.
        route(registry, 0, "outage-*", "down", "up");
        route(registry, 1, "flap-*", "flappy", "up");
        route(registry, 2, "alldown-*", "down", "down2");
        route(registry, 3, "brokenstream-*", "broken", "up");

        registry.add("gateway.resilience.breaker.minimum-calls", () -> 4);
        registry.add("gateway.resilience.breaker.open-duration", () -> "2s");
        registry.add("gateway.resilience.backoff-base", () -> "10ms");
    }

    @Test
    void fullOutageOfThePrimaryEveryRequestStillSucceedsAndTheBreakerSparesIt() throws Exception {
        long downBefore = stats("down");
        long upBefore = stats("up");

        for (int i = 0; i < 30; i++) {
            assertThat(post(chatBody("outage-gpt", false)).statusCode()).as("request " + i).isEqualTo(200);
        }

        long downCalls = stats("down") - downBefore;
        assertThat(stats("up") - upBefore).isEqualTo(30);
        // Without a breaker: 30 requests x 2 attempts = 60 calls to the dead provider.
        // With it: the first few requests trip it, then it is skipped (plus at most a trial or two).
        assertThat(downCalls).isLessThan(15);
    }

    @Test
    void flappingPrimaryEveryRequestStillSucceeds() throws Exception {
        for (int i = 0; i < 40; i++) {
            assertThat(post(chatBody("flap-gpt", false)).statusCode()).as("request " + i).isEqualTo(200);
            Thread.sleep(50); // spread over ~2 s, so the primary goes down and up several times
        }
    }

    @Test
    void allProvidersDownGivesACleanErrorAndBoundedAttempts() throws Exception {
        long before = stats("down") + stats("down2");

        for (int i = 0; i < 5; i++) {
            HttpResponse<String> response = post(chatBody("alldown-gpt", false));
            assertThat(response.statusCode()).isIn(502, 503);
            assertThat(json.readTree(response.body()).at("/error/code").asText())
                    .isIn("provider_error", "provider_circuit_open");
        }

        // At most 2 attempts per provider per request, so never more than 5 x 4 = 20 calls.
        assertThat(stats("down") + stats("down2") - before).isLessThanOrEqualTo(20);
    }

    @Test
    void streamFailsOverBeforeTheFirstByte() throws Exception {
        List<String> data = dataLines(post(chatBody("outage-gpt", true), HttpResponse.BodyHandlers.ofLines()).body());

        assertThat(data.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void streamIsNotFailedOverOnceItHasReachedTheClient() throws Exception {
        long upBefore = stats("up");

        List<String> data = dataLines(
                post(chatBody("brokenstream-gpt", true), HttpResponse.BodyHandlers.ofLines()).body());

        JsonNode error = json.readTree(data.getLast()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("stream_interrupted");
        assertThat(data).doesNotContain("[DONE]");
        assertThat(stats("up") - upBefore).isZero(); // the backup was never asked
    }

    // ---- helpers ----

    private static void route(DynamicPropertyRegistry registry, int index, String pattern, String... providers) {
        String prefix = "gateway.routes[" + index + "].";
        registry.add(prefix + "model-pattern", () -> pattern);
        for (int i = 0; i < providers.length; i++) {
            String name = providers[i];
            registry.add(prefix + "providers[" + i + "].name", () -> name);
        }
    }

    private long stats(String mock) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(MockProviderServer.instance(mock) + "/stats")).GET().build();
        return json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).path("requests").asLong();
    }

    private static List<String> dataLines(java.util.stream.Stream<String> lines) {
        return lines.filter(line -> line.startsWith("data: ")).map(line -> line.substring(6)).toList();
    }
}
