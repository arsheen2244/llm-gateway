package dev.llmgateway.gateway.provider.anthropic;

import java.time.Clock;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderAdapter;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import dev.llmgateway.gateway.provider.ProviderStreams;
import dev.llmgateway.gateway.provider.UpstreamErrors;
import dev.llmgateway.gateway.routing.ProviderConfig;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter for Anthropic's Messages API ({@code POST /v1/messages}). The HTTP
 * calls live here; the format translation lives in {@link AnthropicMapper} and
 * {@link AnthropicStreamTranslator}.
 */
@Component
public class AnthropicAdapter implements ProviderAdapter {

    static final String PATH = "/v1/messages";
    /** The API version this adapter was written against; Anthropic requires it on every call. */
    static final String API_VERSION = "2023-06-01";

    private final RestClient http;
    private final ObjectMapper json;
    private final Clock clock;

    public AnthropicAdapter(RestClient providerRestClient, ObjectMapper json) {
        this.http = providerRestClient;
        this.json = json;
        this.clock = Clock.systemUTC();
    }

    @Override
    public String type() {
        return "anthropic";
    }

    @Override
    public ChatCompletionResponse complete(ProviderConfig provider, ChatCompletionRequest request) {
        try {
            JsonNode message = http.post()
                    .uri(provider.baseUrl() + PATH)
                    .headers(authHeaders(provider))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(AnthropicMapper.toRequest(request, false))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw UpstreamErrors.fromErrorResponse(
                                provider.name(), res.getStatusCode().value(), res.getBody().readAllBytes(), json);
                    })
                    .body(JsonNode.class);
            if (message == null || !message.path("type").asText().equals("message")) {
                throw ProviderException.of(provider.name(), Reason.INVALID_RESPONSE, "Unexpected response body", null);
            }
            return AnthropicMapper.toResponse(message, clock);
        } catch (ResourceAccessException e) {
            throw UpstreamErrors.fromIoFailure(provider.name(), e);
        } catch (RestClientException e) {
            throw ProviderException.of(provider.name(), Reason.INVALID_RESPONSE, "Could not read provider response", e);
        }
    }

    @Override
    public void stream(ProviderConfig provider, ChatCompletionRequest request, ChunkSink sink) {
        AnthropicStreamTranslator translator = new AnthropicStreamTranslator(provider.name(), json, clock);
        try {
            http.post()
                    .uri(provider.baseUrl() + PATH)
                    .headers(authHeaders(provider))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(AnthropicMapper.toRequest(request, true))
                    .exchange((req, res) -> {
                        if (res.getStatusCode().isError()) {
                            throw UpstreamErrors.fromErrorResponse(
                                    provider.name(), res.getStatusCode().value(), res.getBody().readAllBytes(), json);
                        }
                        ProviderStreams.consume(provider.name(), res.getBody(),
                                (event, data) -> translator.onEvent(event, data, sink), translator::finished);
                        return null;
                    });
        } catch (ResourceAccessException e) {
            throw UpstreamErrors.fromIoFailure(provider.name(), e);
        }
    }

    private static Consumer<HttpHeaders> authHeaders(ProviderConfig provider) {
        return headers -> {
            headers.set("x-api-key", provider.apiKey());
            headers.set("anthropic-version", API_VERSION);
        };
    }
}
