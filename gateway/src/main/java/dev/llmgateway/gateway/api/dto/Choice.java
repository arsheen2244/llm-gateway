package dev.llmgateway.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Choice(int index, ChatMessage message, @JsonProperty("finish_reason") String finishReason) {
}
