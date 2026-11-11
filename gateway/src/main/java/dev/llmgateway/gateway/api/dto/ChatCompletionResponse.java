package dev.llmgateway.gateway.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A complete (non-streaming) response, in OpenAI's format. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage) {
}
