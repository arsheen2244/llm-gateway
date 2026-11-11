package dev.llmgateway.gateway.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.provider.ProviderAdapter;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.UpstreamErrors;
import dev.llmgateway.gateway.routing.ProviderConfig;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter for the OpenAI Chat Completions API and anything compatible with it
 * (including our mock). The gateway's own model is OpenAI-shaped, so there is
 * almost nothing to translate: the request is forwarded as it is.
 */
@Component
public class OpenAiAdapter implements ProviderAdapter {

    static final String PATH = "/v1/chat/completions";

    private final RestClient http;
    private final ObjectMapper json;

    public OpenAiAdapter(RestClient providerRestClient, ObjectMapper json) {
        this.http = providerRestClient;
        this.json = json;
    }

    @Override
    public String type() {
        return "openai";
    }

    @Override
    public ChatCompletionResponse complete(ProviderConfig provider, ChatCompletionRequest request) {
        ChatCompletionRequest upstream = request.withStream(false, null);
        try {
            ChatCompletionResponse response = http.post()
                    .uri(provider.baseUrl() + PATH)
                    .headers(h -> h.setBearerAuth(provider.apiKey()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(upstream)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw UpstreamErrors.fromErrorResponse(
                                provider.name(), res.getStatusCode().value(), res.getBody().readAllBytes(), json);
                    })
                    .body(ChatCompletionResponse.class);
            if (response == null) {
                throw ProviderException.of(provider.name(), ProviderException.Reason.INVALID_RESPONSE,
                        "Empty response body", null);
            }
            return response;
        } catch (ResourceAccessException e) {
            throw UpstreamErrors.fromIoFailure(provider.name(), e);
        } catch (RestClientException e) {
            throw ProviderException.of(provider.name(), ProviderException.Reason.INVALID_RESPONSE,
                    "Could not read provider response", e);
        }
    }
}
