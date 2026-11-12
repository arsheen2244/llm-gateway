package dev.llmgateway.gateway.provider.anthropic;

import java.time.Clock;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.api.dto.ChunkChoice;
import dev.llmgateway.gateway.api.dto.Delta;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;

/**
 * Turns Anthropic's named stream events into OpenAI chunks. One instance
 * handles one stream, because it remembers state between events: the message id
 * and model from {@code message_start}, and the token counts.
 *
 * <pre>
 *   Anthropic event        what we emit
 *   message_start          role chunk  (delta: {role: assistant, content: ""})
 *   content_block_delta    content chunk (delta: {content: "..."})
 *   message_delta          finish chunk (finish_reason mapped from stop_reason)
 *   message_stop           usage-only chunk; stream is complete
 *   error                  ProviderException (STREAM_INTERRUPTED)
 *   ping, content_block_start/stop   nothing
 * </pre>
 */
final class AnthropicStreamTranslator {

    private final String provider;
    private final ObjectMapper json;
    private final Clock clock;

    private String id;
    private String model;
    private long created;
    private JsonNode inputUsage;
    private JsonNode outputUsage;
    private boolean finished;

    AnthropicStreamTranslator(String provider, ObjectMapper json, Clock clock) {
        this.provider = provider;
        this.json = json;
        this.clock = clock;
    }

    /** True once message_stop has been seen. */
    boolean finished() {
        return finished;
    }

    void onEvent(String event, String data, ChunkSink sink) {
        JsonNode node = parse(data);
        String type = event != null ? event : node.path("type").asText();
        switch (type) {
            case "message_start" -> {
                JsonNode message = node.path("message");
                id = message.path("id").asText();
                model = message.path("model").asText();
                created = clock.instant().getEpochSecond();
                inputUsage = message.path("usage");
                sink.accept(chunk(new Delta("assistant", ""), null));
            }
            case "content_block_delta" -> {
                JsonNode delta = node.path("delta");
                if (delta.path("type").asText().equals("text_delta")) {
                    sink.accept(chunk(new Delta(null, delta.path("text").asText()), null));
                }
            }
            case "message_delta" -> {
                outputUsage = node.path("usage");
                String finishReason = AnthropicMapper.finishReason(node.path("delta").path("stop_reason").asText(null));
                sink.accept(chunk(new Delta(null, null), finishReason));
            }
            case "message_stop" -> {
                sink.accept(new ChatCompletionChunk(id, "chat.completion.chunk", created, model, List.of(),
                        AnthropicMapper.usage(orEmpty(inputUsage), orEmpty(outputUsage))));
                finished = true;
            }
            case "error" -> throw ProviderException.of(provider, Reason.STREAM_INTERRUPTED,
                    node.path("error").path("message").asText("Provider reported an error mid-stream"), null);
            default -> {
                // ping, content_block_start, content_block_stop, and any future event types
            }
        }
    }

    private ChatCompletionChunk chunk(Delta delta, String finishReason) {
        return new ChatCompletionChunk(id, "chat.completion.chunk", created, model,
                List.of(new ChunkChoice(0, delta, finishReason)), null);
    }

    private JsonNode parse(String data) {
        try {
            return json.readTree(data);
        } catch (JsonProcessingException e) {
            throw ProviderException.of(provider, Reason.INVALID_RESPONSE, "Unparseable stream event", e);
        }
    }

    private JsonNode orEmpty(JsonNode node) {
        return node != null ? node : json.createObjectNode();
    }
}
