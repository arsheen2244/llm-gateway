package dev.llmgateway.gateway.provider.anthropic;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import dev.llmgateway.gateway.api.dto.Choice;
import dev.llmgateway.gateway.api.dto.Usage;

/**
 * Pure translation between the gateway's OpenAI-shaped model and Anthropic's
 * Messages API. It does no I/O, so it is easy to unit test.
 */
final class AnthropicMapper {

    /**
     * Anthropic requires max_tokens; OpenAI does not. 4096 is a safe output
     * limit for every current Claude model. Clients can always send their own.
     */
    static final int DEFAULT_MAX_TOKENS = 4096;

    private AnthropicMapper() {
    }

    /** Anthropic's request body (only the fields we use). Null fields are left out. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            String system,
            List<Message> messages,
            Boolean stream,
            Double temperature,
            @JsonProperty("top_p") Double topP,
            @JsonProperty("stop_sequences") List<String> stopSequences) {
    }

    record Message(String role, String content) {
    }

    /**
     * OpenAI puts system prompts in the message list; Anthropic wants them in a
     * separate top-level field. All system messages are joined in order.
     *
     * <p>Temperature passes through unchanged. OpenAI allows 0-2, Anthropic 0-1.
     * Rescaling silently would change the meaning of the request, so an
     * out-of-range value gets Anthropic's own 400 error instead.
     */
    static MessagesRequest toRequest(ChatCompletionRequest request, boolean stream) {
        List<String> systemParts = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            if (message.role().equals("system")) {
                systemParts.add(message.content());
            } else {
                messages.add(new Message(message.role(), message.content()));
            }
        }
        String system = systemParts.isEmpty() ? null : String.join("\n\n", systemParts);
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : DEFAULT_MAX_TOKENS;
        return new MessagesRequest(request.model(), maxTokens, system, messages, stream ? Boolean.TRUE : null,
                request.temperature(), request.topP(), request.stop());
    }

    /** Anthropic's message response becomes an OpenAI chat completion. */
    static ChatCompletionResponse toResponse(JsonNode message, Clock clock) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : message.path("content")) {
            if (block.path("type").asText().equals("text")) {
                text.append(block.path("text").asText());
            }
        }
        Choice choice = new Choice(0, new ChatMessage("assistant", text.toString()),
                finishReason(message.path("stop_reason").asText(null)));
        return new ChatCompletionResponse(message.path("id").asText(), "chat.completion",
                clock.instant().getEpochSecond(), message.path("model").asText(), List.of(choice),
                usage(message.path("usage"), message.path("usage")));
    }

    /**
     * Maps Anthropic's stop_reason to OpenAI's finish_reason. Unknown values
     * become "stop", the most neutral choice.
     */
    static String finishReason(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "refusal" -> "content_filter";
            default -> "stop"; // end_turn, stop_sequence, pause_turn, ...
        };
    }

    /**
     * Anthropic reports prompt-cache tokens separately from {@code input_tokens}.
     * They are still input the model processed, so they count toward OpenAI's
     * prompt_tokens. Otherwise week 5's cost numbers would be too low.
     * In streams, input counts arrive in message_start and the output count in
     * message_delta, so the two sources are passed separately.
     */
    static Usage usage(JsonNode inputSource, JsonNode outputSource) {
        int input = inputSource.path("input_tokens").asInt(0)
                + inputSource.path("cache_creation_input_tokens").asInt(0)
                + inputSource.path("cache_read_input_tokens").asInt(0);
        return Usage.of(input, outputSource.path("output_tokens").asInt(0));
    }
}
