package dev.llmgateway.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.Test;

/** Auth through the real gateway: the key is checked before anything else happens. */
class AuthIntegrationTest extends GatewayIntegrationTest {

    @Test
    void requestWithValidKeySucceeds() throws Exception {
        assertThat(post(chatBody("mock-gpt", false)).statusCode()).isEqualTo(200);
    }

    @Test
    void requestWithoutKeyIs401AndCarriesRequestId() throws Exception {
        HttpResponse<String> response = send(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).at("/error/code").asText()).isEqualTo("missing_api_key");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void requestWithWrongKeyIs401EvenForAnInvalidBody() throws Exception {
        // The body is never parsed: auth rejects first, so this is 401, not 400.
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer not-a-real-key")
                .POST(HttpRequest.BodyPublishers.ofString("{not json"))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).at("/error/code").asText()).isEqualTo("invalid_api_key");
    }

    private HttpResponse<String> send(String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(chatBody("mock-gpt", false)));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
