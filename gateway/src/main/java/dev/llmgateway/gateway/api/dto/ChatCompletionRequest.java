package dev.llmgateway.gateway.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /v1/chat/completions}, in OpenAI's format.
 *
 * <p>This record is both the public API and the gateway's internal model (design
 * note 6). Fields outside the supported subset (for example {@code tools}) are
 * ignored. Null fields are left out when the record is serialized, so the request
 * we forward upstream contains only what the client sent.
 *
 * <p>Helper methods are deliberately not named {@code isX()} or {@code getX()},
 * so Jackson does not mistake them for extra JSON properties.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(
        @NotBlank String model,
        @NotEmpty List<@Valid ChatMessage> messages,
        Boolean stream,
        @JsonProperty("stream_options") StreamOptions streamOptions,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        @JsonProperty("max_tokens") @Positive Integer maxTokens,
        @JsonProperty("top_p") @DecimalMin("0.0") @DecimalMax("1.0") Double topP,
        // OpenAI accepts "stop" as either one string or a list of strings.
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> stop) {

    public boolean streaming() {
        return Boolean.TRUE.equals(stream);
    }

    /** Whether the client asked for a final usage chunk at the end of a stream. */
    public boolean usageRequested() {
        return streamOptions != null && Boolean.TRUE.equals(streamOptions.includeUsage());
    }

    /** Copy with {@code stream} set as given and stream options cleared. */
    public ChatCompletionRequest withStream(boolean newStream, StreamOptions newStreamOptions) {
        return new ChatCompletionRequest(model, messages, newStream, newStreamOptions,
                temperature, maxTokens, topP, stop);
    }
}
