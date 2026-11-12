package dev.llmgateway.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code finish_reason} is null until the last content chunk. It is written as
 * an explicit null, like OpenAI does, rather than left out.
 */
public record ChunkChoice(int index, Delta delta, @JsonProperty("finish_reason") String finishReason) {
}
