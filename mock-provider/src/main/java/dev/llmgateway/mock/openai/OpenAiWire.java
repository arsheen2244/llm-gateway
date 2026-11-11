package dev.llmgateway.mock.openai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The subset of OpenAI's Chat Completions wire format that the mock needs.
 * These records belong to the mock alone and are not shared with the gateway
 * (see design note 1).
 */
final class OpenAiWire {

    private OpenAiWire() {
    }

    record ChatRequest(
            String model,
            List<JsonNode> messages,
            Boolean stream,
            @JsonProperty("stream_options") StreamOptions streamOptions) {

        boolean isStream() {
            return Boolean.TRUE.equals(stream);
        }

        boolean includeUsage() {
            return streamOptions != null && Boolean.TRUE.equals(streamOptions.includeUsage());
        }
    }

    record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {
    }

    record Message(String role, String content) {
    }

    record Choice(int index, Message message, @JsonProperty("finish_reason") String finishReason) {
    }

    record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {
    }

    record ChatCompletion(
            String id, String object, long created, String model, List<Choice> choices, Usage usage) {
    }

    /** In a stream, each chunk carries only the new piece ("delta"), and absent fields are omitted. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Delta(String role, String content) {
    }

    record ChunkChoice(int index, Delta delta, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Chunk(String id, String object, long created, String model, List<ChunkChoice> choices, Usage usage) {
    }

    record ErrorBody(ErrorDetail error) {
    }

    record ErrorDetail(String message, String type, String param, String code) {
    }
}
