package dev.llmgateway.gateway.usage.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import dev.llmgateway.gateway.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@code GET /v1/usage} over HTTP, with made-up usage for one client. */
@Import(UsageEndpointIntegrationTest.FakeReaderConfig.class)
class UsageEndpointIntegrationTest extends GatewayIntegrationTest {

    private static final String OTHER_KEY = "other-client-key";

    /** test-client has used something on 2026-11-20; nobody else has used anything. */
    @TestConfiguration
    static class FakeReaderConfig {
        @Bean
        @Primary
        UsageReader fakeReader() {
            return (clientId, from, to) -> clientId.equals("test-client")
                    ? List.of(new DailyUsage(LocalDate.of(2026, 11, 20), new UsageTotals(9, 5, 2, 1, 3, 100, 50,
                            30, 20, new PlainDecimal(new BigDecimal("0.000200000000")), 1)))
                    : List.of();
        }
    }

    @DynamicPropertySource
    static void otherClient(DynamicPropertyRegistry registry) {
        registry.add("gateway.auth.clients[1].id", () -> "other-client");
        registry.add("gateway.auth.clients[1].key-sha256", () -> TestKeys.sha256(OTHER_KEY));
        registry.add("gateway.auth.clients[1].requests-per-minute", () -> 100);
        registry.add("gateway.auth.clients[1].tokens-per-minute", () -> 100_000);
    }

    @Test
    void returnsTheCallersUsage() throws Exception {
        HttpResponse<String> response = usage("?from=2026-11-01&to=2026-11-30", TEST_KEY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree("""
                {"object":"usage","client_id":"test-client","from":"2026-11-01","to":"2026-11-30",
                 "total":{"requests":9,"successful_requests":5,"rate_limited_requests":2,"failed_requests":1,
                   "cache_hits":3,"prompt_tokens":100,"completion_tokens":50,"cached_prompt_tokens":30,
                   "cached_completion_tokens":20,"cost_usd":"0.0002","unpriced_requests":1},
                 "days":[{"date":"2026-11-20","requests":9,"successful_requests":5,"rate_limited_requests":2,
                   "failed_requests":1,"cache_hits":3,"prompt_tokens":100,"completion_tokens":50,
                   "cached_prompt_tokens":30,"cached_completion_tokens":20,"cost_usd":"0.0002",
                   "unpriced_requests":1}]}"""));
    }

    @Test
    void aClientCannotAskForAnotherClientsUsage() throws Exception {
        HttpResponse<String> response = usage("?client_id=test-client&clientId=test-client", OTHER_KEY);

        JsonNode body = json.readTree(response.body());
        assertThat(body.path("client_id").asText()).isEqualTo("other-client");
        assertThat(body.at("/total/requests").asLong()).isZero();
        assertThat(body.path("days")).isEmpty();
    }

    @Test
    void withoutAValidKeyItIs401() throws Exception {
        assertThat(usage("", "wrong-key").statusCode()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?from=yesterday", "?to=2026-02-30", "?from=2026-11-02&to=2026-11-01",
            "?from=2020-01-01&to=2026-01-01"})
    void badDatesAre400InOpenAiFormat(String query) throws Exception {
        HttpResponse<String> response = usage(query, TEST_KEY);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).at("/error/type").asText()).isEqualTo("invalid_request_error");
    }

    private HttpResponse<String> usage(String query, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/usage" + query))
                .header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
