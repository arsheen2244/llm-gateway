package dev.llmgateway.gateway.auth;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The gateway's own clients, under {@code gateway.auth.clients} (design note 17).
 *
 * <p>The default is <b>no clients</b>, so every request is rejected until clients are
 * configured: forgetting to configure auth leaves the gateway locked, not open.
 *
 * @param clients who may call the gateway, and their limits
 */
@Validated
@ConfigurationProperties(prefix = "gateway.auth")
public record AuthProperties(@DefaultValue List<@Valid Client> clients) {

    /**
     * One client. Only the SHA-256 hash of its key is stored, so a leaked config
     * file does not leak usable keys.
     *
     * @param id                a stable name used in logs and rate-limit keys
     * @param keySha256         hex SHA-256 of the client's API key
     * @param requestsPerMinute request limit for a sliding one-minute window
     * @param tokensPerMinute   token limit for a sliding one-minute window
     */
    public record Client(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "must be 1-64 chars of [A-Za-z0-9._-]")
            String id,
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}", message = "must be a 64-character hex SHA-256")
            String keySha256,
            @Positive int requestsPerMinute,
            @Positive long tokensPerMinute) {
    }
}
