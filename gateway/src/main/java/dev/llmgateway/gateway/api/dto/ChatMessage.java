package dev.llmgateway.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** One message in a conversation. Week 1 supports plain-text content only. */
public record ChatMessage(
        @NotBlank @Pattern(regexp = "system|user|assistant", message = "must be system, user or assistant")
        String role,
        @NotNull String content) {
}
