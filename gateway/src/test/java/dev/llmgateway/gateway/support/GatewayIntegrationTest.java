package dev.llmgateway.gateway.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for end-to-end tests: a real gateway on a random port, wired to a
 * real mock provider.
 *
 * <p>Safety: the real providers' API keys are forced to empty, so a key set in
 * the developer's environment can never make a test call a paid API.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateway.providers.openai.api-key=", "gateway.providers.anthropic.api-key=",
        // Many tests here send deliberate errors to the same mock; a real breaker would trip
        // part-way through the suite. Fault-injection tests configure a realistic one.
        "gateway.resilience.breaker.minimum-calls=1000000",
        "gateway.resilience.backoff-base=10ms"})
public abstract class GatewayIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper json;

    protected final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** The test client's API key. Only its SHA-256 hash goes into the gateway's config. */
    protected static final String TEST_KEY = "test-client-key";

    @DynamicPropertySource
    static void pointProvidersAtMock(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.mock-openai.base-url", MockProviderServer::baseUrl);
        registry.add("gateway.providers.mock-anthropic.base-url", MockProviderServer::baseUrl);
        registry.add("gateway.auth.clients[0].id", () -> "test-client");
        registry.add("gateway.auth.clients[0].key-sha256", () -> TestKeys.sha256(TEST_KEY));
        registry.add("gateway.auth.clients[0].requests-per-minute", () -> 1_000_000);
        registry.add("gateway.auth.clients[0].tokens-per-minute", () -> 1_000_000_000L);
    }

    /** A POST to the chat endpoint with JSON content type and the test client's key. */
    protected HttpRequest.Builder chatRequest() {
        return chatRequest(TEST_KEY);
    }

    /** Same, sent as the client that owns {@code apiKey}. */
    protected HttpRequest.Builder chatRequest(String apiKey) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey);
    }

    protected <T> HttpResponse<T> post(String body, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return http.send(chatRequest().POST(HttpRequest.BodyPublishers.ofString(body)).build(), handler);
    }

    protected HttpResponse<String> post(String body) throws IOException, InterruptedException {
        return post(body, HttpResponse.BodyHandlers.ofString());
    }

    protected static String chatBody(String model, boolean stream) {
        return """
                {"model":"%s","stream":%s,"messages":[{"role":"user","content":"Say hello"}]}"""
                .formatted(model, stream);
    }
}
