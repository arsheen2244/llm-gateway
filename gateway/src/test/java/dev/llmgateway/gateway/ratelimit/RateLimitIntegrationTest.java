package dev.llmgateway.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import dev.llmgateway.gateway.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Rate limiting through the real gateway and mock. Each test uses its own client with
 * small limits, so tests never share a window.
 *
 * <p>Token arithmetic: the prompt "Say hello" is 9 characters, so it is estimated at 3
 * tokens, and requests set max_tokens. The mock reports 3 prompt + 3 completion = 6 actual
 * tokens.
 */
class RateLimitIntegrationTest extends GatewayIntegrationTest {

    @DynamicPropertySource
    static void limitedClients(DynamicPropertyRegistry registry) {
        client(registry, 1, "two-requests", 2, 1_000_000);
        client(registry, 2, "reconcile", 1_000, 100);
        client(registry, 3, "reconcile-stream", 1_000, 100);
        client(registry, 4, "refund", 1_000, 100);
        client(registry, 5, "too-large", 1_000, 100);
    }

    @Test
    void allowedResponsesCarryRateLimitHeaders() throws Exception {
        HttpResponse<String> response = post(chatBody("mock-gpt", false));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("x-ratelimit-limit-requests")).hasValue("1000000");
        assertThat(response.headers().firstValue("x-ratelimit-remaining-tokens")).isPresent();
    }

    @Test
    void requestOverTheRequestLimitGets429WithRetryAfter() throws Exception {
        assertThat(send("two-requests", body(false, 10)).statusCode()).isEqualTo(200);
        assertThat(send("two-requests", body(false, 10)).statusCode()).isEqualTo(200);

        HttpResponse<String> third = send("two-requests", body(true, 10)); // streaming: still a plain 429

        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(third.headers().firstValue("x-ratelimit-remaining-requests")).hasValue("0");
        assertThat(Long.parseLong(third.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 60L);
        JsonNode error = json.readTree(third.body()).path("error");
        assertThat(error.path("type").asText()).isEqualTo("rate_limit_error");
        assertThat(error.path("code").asText()).isEqualTo("rate_limit_exceeded");
        assertThat(error.path("message").asText()).contains("requests per minute");
    }

    @Test
    void reconcilingWithActualUsageFreesTokensForTheNextRequest() throws Exception {
        // Each request reserves 3 + 90 = 93 of 100 tokens. Without reconcile the second
        // would be refused (93 + 93 > 100); the first is reconciled down to 6, so 6 + 93 fits.
        assertThat(send("reconcile", body(false, 90)).statusCode()).isEqualTo(200);
        assertThat(send("reconcile", body(false, 90)).statusCode()).isEqualTo(200);
    }

    @Test
    void streamsAreReconciledFromTheFinalUsageChunk() throws Exception {
        assertThat(send("reconcile-stream", body(true, 90)).statusCode()).isEqualTo(200);
        assertThat(send("reconcile-stream", body(true, 90)).statusCode()).isEqualTo(200);
    }

    @Test
    void providerErrorRefundsTheReservedTokens() throws Exception {
        String failing = """
                {"model":"mock-error-500","max_tokens":90,"messages":[{"role":"user","content":"Say hello"}]}""";
        assertThat(send("refund", failing).statusCode()).isEqualTo(502);

        // 3 + 95 = 98 fits only if the failed request's 93 tokens were given back.
        assertThat(send("refund", body(false, 95)).statusCode()).isEqualTo(200);
    }

    @Test
    void estimateAboveTheWholeLimitIs429WithoutRetryAfter() throws Exception {
        HttpResponse<String> response = send("too-large", body(false, 500));

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).isEmpty();
        assertThat(json.readTree(response.body()).at("/error/code").asText())
                .isEqualTo("request_too_large_for_limit");
    }

    // ---- helpers ----

    private HttpResponse<String> send(String clientId, String body) throws Exception {
        HttpRequest request = chatRequest(keyOf(clientId)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String body(boolean stream, int maxTokens) {
        return """
                {"model":"mock-gpt","stream":%s,"max_tokens":%d,"messages":[{"role":"user","content":"Say hello"}]}"""
                .formatted(stream, maxTokens);
    }

    private static String keyOf(String clientId) {
        return "key-" + clientId;
    }

    private static void client(DynamicPropertyRegistry registry, int index, String id, int rpm, long tpm) {
        String prefix = "gateway.auth.clients[" + index + "].";
        registry.add(prefix + "id", () -> id);
        registry.add(prefix + "key-sha256", () -> TestKeys.sha256(keyOf(id)));
        registry.add(prefix + "requests-per-minute", () -> rpm);
        registry.add(prefix + "tokens-per-minute", () -> tpm);
    }
}
