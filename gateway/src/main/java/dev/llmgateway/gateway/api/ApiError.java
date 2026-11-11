package dev.llmgateway.gateway.api;

import dev.llmgateway.gateway.api.dto.ErrorResponse;

/** An HTTP status plus the OpenAI-style error body to send with it. */
public record ApiError(int status, ErrorResponse body) {

    static ApiError of(int status, String message, String type, String code) {
        return new ApiError(status, ErrorResponse.of(message, type, code));
    }
}
