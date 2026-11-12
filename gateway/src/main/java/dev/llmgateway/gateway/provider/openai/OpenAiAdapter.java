package dev.llmgateway.gateway.provider.openai;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.api.dto.StreamOptions;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderAdapter;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import dev.llmgateway.gateway.provider.SseEventReader;
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
 * almost nothing to translate: requests and chunks pass through as they are.
 */
@Component
public class OpenAiAdapter implements ProviderAdapter {

    static final String PATH = "/v1/chat/completions";
    private static final String DONE = "[DONE]";

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
                throw ProviderException.of(provider.name(), Reason.INVALID_RESPONSE, "Empty response body", null);
            }
            return response;
        } catch (ResourceAccessException e) {
            throw UpstreamErrors.fromIoFailure(provider.name(), e);
        } catch (RestClientException e) {
            throw ProviderException.of(provider.name(), Reason.INVALID_RESPONSE, "Could not read provider response", e);
        }
    }

    @Override
    public void stream(ProviderConfig provider, ChatCompletionRequest request, ChunkSink sink) {
        // Always ask for the final usage chunk: week 5's cost tracking needs it.
        // Whether the *client* sees it is decided later, by what the client asked for.
        ChatCompletionRequest upstream = request.withStream(true, new StreamOptions(true));
        try {
            http.post()
                    .uri(provider.baseUrl() + PATH)
                    .headers(h -> h.setBearerAuth(provider.apiKey()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(upstream)
                    .exchange((req, res) -> {
                        if (res.getStatusCode().isError()) {
                            throw UpstreamErrors.fromErrorResponse(
                                    provider.name(), res.getStatusCode().value(), res.getBody().readAllBytes(), json);
                        }
                        consumeStream(provider.name(), res.getBody(), sink);
                        return null;
                    });
        } catch (ResourceAccessException e) {
            // An I/O failure before any event arrived: same handling as for non-streaming calls.
            throw UpstreamErrors.fromIoFailure(provider.name(), e);
        }
    }

    /**
     * Reads OpenAI's SSE stream and pushes each chunk to the sink. The body is
     * always closed on the way out. Closing it before the stream has ended makes
     * HttpURLConnection drop the socket, so if the client has gone away we stop
     * receiving (and paying for) tokens nobody will read.
     */
    void consumeStream(String provider, InputStream body, ChunkSink sink) throws IOException {
        StreamProgress progress = new StreamProgress();
        try (body) {
            SseEventReader.read(body, (event, data) -> {
                progress.events++;
                if (DONE.equals(data)) {
                    progress.done = true;
                    return;
                }
                sink.accept(toChunk(provider, data));
            });
        } catch (IOException e) {
            if (progress.events == 0) {
                throw e; // nothing arrived yet: an ordinary I/O failure
            }
            throw ProviderException.of(provider, Reason.STREAM_INTERRUPTED,
                    "Stream broke after it started: " + e.getMessage(), e);
        }
        if (!progress.done) {
            throw ProviderException.of(provider, Reason.STREAM_INTERRUPTED, "Stream ended without [DONE]", null);
        }
    }

    private ChatCompletionChunk toChunk(String provider, String data) {
        try {
            JsonNode node = json.readTree(data);
            if (node.hasNonNull("error")) {
                // OpenAI can report a failure inside the stream instead of dropping it.
                String message = node.path("error").path("message").asText("Provider reported an error mid-stream");
                throw ProviderException.of(provider, Reason.STREAM_INTERRUPTED, message, null);
            }
            return json.treeToValue(node, ChatCompletionChunk.class);
        } catch (JsonProcessingException e) {
            throw ProviderException.of(provider, Reason.INVALID_RESPONSE, "Unparseable stream chunk", e);
        }
    }

    /** Mutable counters updated from inside the listener lambda. */
    private static final class StreamProgress {
        int events;
        boolean done;
    }
}
