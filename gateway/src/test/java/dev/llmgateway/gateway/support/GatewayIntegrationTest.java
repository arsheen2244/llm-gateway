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
        properties = {"gateway.providers.openai.api-key="})
public abstract class GatewayIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper json;

    protected final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @DynamicPropertySource
    static void pointProvidersAtMock(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.mock-openai.base-url", MockProviderServer::baseUrl);
    }

    protected <T> HttpResponse<T> post(String body, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, handler);
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
