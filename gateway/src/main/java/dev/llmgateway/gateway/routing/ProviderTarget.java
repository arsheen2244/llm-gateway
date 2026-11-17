package dev.llmgateway.gateway.routing;

/**
 * One provider to try for a model, in failover order.
 *
 * @param provider      the provider instance
 * @param modelOverride the model name to send instead of the client's, or null to keep it
 */
public record ProviderTarget(ProviderConfig provider, String modelOverride) {

    /** The model name to send to this provider for a client that asked for {@code requested}. */
    public String upstreamModel(String requested) {
        return modelOverride == null || modelOverride.isBlank() ? requested : modelOverride;
    }
}
