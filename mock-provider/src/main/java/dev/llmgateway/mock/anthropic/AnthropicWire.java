package dev.llmgateway.mock.anthropic;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** The subset of Anthropic's Messages API wire format that the mock needs. */
final class AnthropicWire {

    private AnthropicWire() {
    }

    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") Integer maxTokens,
            JsonNode system,
            List<JsonNode> messages,
            Boolean stream) {

        boolean isStream() {
            return Boolean.TRUE.equals(stream);
        }
    }

    record TextBlock(String type, String text) {
        static TextBlock of(String text) {
            return new TextBlock("text", text);
        }
    }

    record Usage(@JsonProperty("input_tokens") int inputTokens, @JsonProperty("output_tokens") int outputTokens) {
    }

    record Message(
            String id, String type, String role, String model, List<TextBlock> content,
            @JsonProperty("stop_reason") String stopReason,
            @JsonProperty("stop_sequence") String stopSequence,
            Usage usage) {
    }

    // ---- streaming events: each is sent as "event: <type>" + "data: <json>" ----

    record MessageStart(String type, Message message) {
    }

    record ContentBlockStart(String type, int index, @JsonProperty("content_block") TextBlock contentBlock) {
    }

    record TextDelta(String type, String text) {
    }

    record ContentBlockDelta(String type, int index, TextDelta delta) {
    }

    record ContentBlockStop(String type, int index) {
    }

    record StopInfo(@JsonProperty("stop_reason") String stopReason, @JsonProperty("stop_sequence") String stopSequence) {
    }

    record OutputUsage(@JsonProperty("output_tokens") int outputTokens) {
    }

    record MessageDelta(String type, StopInfo delta, OutputUsage usage) {
    }

    record TypeOnly(String type) {
    }

    // ---- errors ----

    record ErrorBody(String type, ErrorDetail error) {
    }

    record ErrorDetail(String type, String message) {
    }
}
