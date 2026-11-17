package dev.llmgateway.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import dev.llmgateway.gateway.support.MockProviderServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Hedging through the real gateway: a mock that is always slow (800 ms), a hedge delay of
 * 150 ms, and hedging allowed for every request. Checks the wiring: a hedge is really sent,
 * the client still gets one normal answer, and streams are never hedged.
 */
class HedgingIntegrationTest extends GatewayIntegrationTest {

    @DynamicPropertySource
    static void slowProvider(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.slowtail.type", () -> "openai");
        registry.add("gateway.providers.slowtail.base-url", () -> MockProviderServer.instance("slowtail",
                "--mock.slow-rate=1.0", "--mock.slow-latency-ms=800"));
        registry.add("gateway.providers.slowtail.api-key", () -> "mock-key");
        registry.add("gateway.routes[0].model-pattern", () -> "slow-*");
        registry.add("gateway.routes[0].provider", () -> "slowtail");

        registry.add("gateway.resilience.hedging.default-delay", () -> "150ms");
        registry.add("gateway.resilience.hedging.floor", () -> "50ms");
        registry.add("gateway.resilience.hedging.min-samples", () -> 1_000_000); // always the default delay
        registry.add("gateway.resilience.hedging.max-ratio", () -> 1.0);
    }

    @Test
    void slowCallIsHedgedAndTheClientGetsOneAnswer() throws Exception {
        long before = stats();

        HttpResponse<String> response = post(chatBody("slow-gpt", false));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).at("/object").asText()).isEqualTo("chat.completion");
        assertThat(stats() - before).isEqualTo(2); // the primary and one hedge
    }

    @Test
    void streamsAreNeverHedged() throws Exception {
        long before = stats();

        HttpResponse<String> response = post(chatBody("slow-gpt", true));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("data: [DONE]");
        assertThat(stats() - before).isEqualTo(1);
    }

    private long stats() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(MockProviderServer.instance("slowtail") + "/stats")).GET().build();
        return json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).path("requests").asLong();
    }
}
