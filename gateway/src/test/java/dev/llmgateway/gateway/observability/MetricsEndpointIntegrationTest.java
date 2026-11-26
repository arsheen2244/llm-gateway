package dev.llmgateway.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import dev.llmgateway.gateway.resilience.CircuitBreaker;
import dev.llmgateway.gateway.resilience.ProviderGuards;
import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.TestPropertySource;

/** What Prometheus sees: the metrics endpoint of a real gateway, on its own port. */
@AutoConfigureObservability(tracing = false) // tests switch metrics export off by default
@TestPropertySource(properties = {
        "management.server.port=0",
        "gateway.usage.sink=kafka",
        "gateway.usage.kafka.bootstrap-servers=127.0.0.1:1",
        "gateway.usage.kafka.shutdown-timeout=0s",
        "gateway.resilience.breaker.minimum-calls=3"})
class MetricsEndpointIntegrationTest extends GatewayIntegrationTest {

    @LocalManagementPort
    int managementPort;

    @Autowired
    ProviderGuards guards;

    @Test
    void requestsAreCountedByOutcomeWithLatencyBuckets() throws Exception {
        String body = """
                {"model":"mock-gpt","temperature":0,"messages":[{"role":"user","content":"Metrics %s"}]}"""
                .formatted(UUID.randomUUID());
        double missesBefore = requests("status=\"success\"", "cache=\"miss\"");
        post(body);
        post(body); // from the cache

        assertThat(requests("status=\"success\"", "cache=\"miss\"") - missesBefore).isEqualTo(1);
        assertThat(requests("status=\"success\"", "cache=\"exact\"", "provider=\"none\"")).isGreaterThanOrEqualTo(1);
        assertThat(scrape()).contains("gateway_requests_seconds_bucket{").contains("le=\"0.1\"");
    }

    @Test
    void breakerStateFollowsTheBreaker() throws Exception {
        assertThat(value("gateway_breaker_state", "provider=\"mock-anthropic\"")).isZero();
        CircuitBreaker breaker = guards.forProvider("mock-anthropic").breaker();
        for (int i = 0; i < 3; i++) {
            breaker.tryAcquirePermission();
            breaker.onFailure();
        }

        assertThat(value("gateway_breaker_state", "provider=\"mock-anthropic\"")).isEqualTo(2);
        assertThat(value("gateway_breaker_state", "provider=\"openai\"")).isZero();
    }

    @Test
    void usageEventCountersAreExposed() throws Exception {
        assertThat(value("usage_events_dropped_total")).isZero();
        assertThat(value("usage_events_sent_total")).isZero(); // there is no Kafka in this test
        assertThat(scrape()).contains("usage_events_queued");
    }

    @Test
    void metricsAreNotServedOnThePublicPort() throws Exception {
        for (String path : List.of("/actuator/prometheus", "/actuator", "/actuator/health")) {
            assertThat(get(port, path).statusCode()).as(path).isEqualTo(404);
        }
        // And on the metrics port, nothing but metrics.
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
    }

    // ---- helpers ----

    private double requests(String... tags) throws Exception {
        return value("gateway_requests_seconds_count", tags);
    }

    /** The sum of a metric's values over all series having these tags; 0 if there are none. */
    private double value(String metric, String... tags) throws Exception {
        return scrape().lines()
                .filter(line -> line.startsWith(metric + "{") || line.startsWith(metric + " "))
                .filter(line -> List.of(tags).stream().allMatch(line::contains))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }

    private String scrape() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private HttpResponse<String> get(int onPort, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + onPort + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
