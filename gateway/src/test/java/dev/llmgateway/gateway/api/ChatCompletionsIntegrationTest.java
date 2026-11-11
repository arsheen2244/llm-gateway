package dev.llmgateway.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.Test;

/** Non-streaming requests through the real gateway to the real mock. */
class ChatCompletionsIntegrationTest extends GatewayIntegrationTest {

    @Test
    void normalRequestIsProxiedToMockAndReturnedInOpenAiFormat() throws Exception {
        HttpResponse<String> response = post(chatBody("mock-gpt", false));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.at("/object").asText()).isEqualTo("chat.completion");
        assertThat(body.at("/model").asText()).isEqualTo("mock-gpt");
        assertThat(body.at("/choices/0/message/role").asText()).isEqualTo("assistant");
        assertThat(body.at("/choices/0/message/content").asText()).isEqualTo("This is a");
        assertThat(body.at("/usage/total_tokens").asInt()).isPositive();
    }

    @Test
    void providerServerErrorBecomes502() throws Exception {
        HttpResponse<String> response = post(chatBody("mock-error-500", false));

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json.readTree(response.body()).at("/error/code").asText()).isEqualTo("provider_error");
    }

    @Test
    void providerRateLimitStays429() throws Exception {
        HttpResponse<String> response = post(chatBody("mock-error-429", false));

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(json.readTree(response.body()).at("/error/type").asText()).isEqualTo("rate_limit_error");
    }

    @Test
    void providerBadRequestPassesThroughWithProviderMessage() throws Exception {
        HttpResponse<String> response = post(chatBody("mock-error-400", false));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).at("/error/message").asText()).contains("Simulated provider error");
    }

    @Test
    void unknownModelIs404ModelNotFound() throws Exception {
        HttpResponse<String> response = post(chatBody("llama-unknown", false));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json.readTree(response.body()).at("/error/code").asText()).isEqualTo("model_not_found");
    }

    @Test
    void providerWithoutApiKeyIs503WithoutCallingIt() throws Exception {
        HttpResponse<String> response = post(chatBody("gpt-4o-mini", false));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).at("/error/code").asText()).isEqualTo("provider_not_configured");
    }

    @Test
    void invalidRequestIs400WithFieldDetails() throws Exception {
        HttpResponse<String> response = post("""
                {"model":"mock-gpt","messages":[],"temperature":5}""");

        assertThat(response.statusCode()).isEqualTo(400);
        String message = json.readTree(response.body()).at("/error/message").asText();
        assertThat(message).contains("messages").contains("temperature");
    }

    @Test
    void malformedJsonIs400() throws Exception {
        HttpResponse<String> response = post("{not json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).at("/error/code").asText()).isEqualTo("invalid_json");
    }
}
