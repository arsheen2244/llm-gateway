package dev.llmgateway.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Token counts. Week 5's cost accounting is built on these numbers. */
public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens) {

    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens, promptTokens + completionTokens);
    }
}
