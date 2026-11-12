package dev.llmgateway.gateway.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One streamed piece of a response ({@code "object": "chat.completion.chunk"}).
 * The optional last chunk has no choices and carries only {@code usage}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionChunk(
        String id,
        String object,
        long created,
        String model,
        List<ChunkChoice> choices,
        Usage usage) {

    /** True for the final usage-only chunk that OpenAI sends when asked. */
    public boolean usageOnly() {
        return (choices == null || choices.isEmpty()) && usage != null;
    }
}
