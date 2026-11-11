package dev.llmgateway.gateway.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Everything under {@code gateway:} in application.yml. It is validated at
 * startup, so a broken config stops the app immediately instead of failing on
 * the first request.
 *
 * @param providers provider instances by name, e.g. "openai" or "mock-openai"
 * @param routes    ordered model-name rules; the first match wins
 * @param timeouts  timeouts for calls to providers
 */
@Validated
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @NotEmpty Map<String, @Valid Provider> providers,
        @NotEmpty List<@Valid Route> routes,
        @Valid @DefaultValue Timeouts timeouts) {

    /**
     * One provider instance: which protocol it speaks ({@code type}), where it
     * lives, and its key. The key comes from an environment variable through a
     * {@code ${...}} placeholder in application.yml, so it is never committed.
     */
    public record Provider(@NotBlank String type, @NotBlank String baseUrl, String apiKey) {
        @Override
        public String toString() { // never print the key
            return "Provider[type=" + type + ", baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<none>" : "****") + "]";
        }
    }

    /** {@code modelPattern} is an exact name, or a prefix ending in "*" such as "gpt-*". */
    public record Route(@NotBlank String modelPattern, @NotBlank String provider) {
    }

    /**
     * @param connect how long to wait for a TCP connection to a provider
     * @param read    how long to wait for the provider to start responding
     */
    public record Timeouts(@DefaultValue("5s") Duration connect, @DefaultValue("60s") Duration read) {
    }
}
