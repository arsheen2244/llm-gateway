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

    /**
     * {@code modelPattern} is an exact name, or a prefix ending in "*" such as "gpt-*".
     * Set exactly one of {@code provider} (one provider) or {@code providers} (an ordered
     * failover list; design note 25). The router checks this at startup.
     */
    public record Route(@NotBlank String modelPattern, String provider, List<@Valid Target> providers) {

        /** The single-provider form, as used before week 3. */
        public static Route single(String modelPattern, String provider) {
            return new Route(modelPattern, provider, null);
        }
    }

    /**
     * One entry of a failover list.
     *
     * @param name  the provider instance
     * @param model the model name to send to that provider; empty means "the model the
     *              client asked for". Needed when failing over across protocols, e.g. from
     *              an OpenAI model to an Anthropic one.
     */
    public record Target(@NotBlank String name, String model) {
    }

    /**
     * @param connect how long to wait for a TCP connection to a provider
     * @param read              longest allowed silence while waiting for the provider:
     *                          before the first byte and between bytes of a stream.
     *                          It is not a cap on total duration, so long streams are fine.
     * @param firstByte         the provider's response headers must arrive within this
     * @param total             a non-streaming call must finish within this
     * @param streamMaxDuration a stream must finish within this (instead of {@code total},
     *                          so long, healthy answers aren't cut off; design note 26)
     */
    public record Timeouts(
            @DefaultValue("5s") Duration connect,
            @DefaultValue("60s") Duration read,
            @DefaultValue("60s") Duration firstByte,
            @DefaultValue("120s") Duration total,
            @DefaultValue("10m") Duration streamMaxDuration) {

        /** Connect and idle only, with the other deadlines at their defaults. */
        public static Timeouts of(Duration connect, Duration read) {
            return new Timeouts(connect, read, Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofMinutes(10));
        }
    }
}
