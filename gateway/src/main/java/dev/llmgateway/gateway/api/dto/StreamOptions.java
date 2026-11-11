package dev.llmgateway.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code stream_options}: {@code include_usage=true} asks for a final usage chunk. */
public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {
}
