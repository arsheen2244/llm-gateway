package dev.llmgateway.gateway.routing;

/**
 * A resolved provider instance: what an adapter needs to make a call.
 *
 * @param name    config name, e.g. "mock-openai" (used in logs and errors)
 * @param type    protocol, e.g. "openai" (selects the adapter)
 * @param baseUrl e.g. "https://api.openai.com"
 * @param apiKey  secret; never printed
 */
public record ProviderConfig(String name, String type, String baseUrl, String apiKey) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "ProviderConfig[name=" + name + ", type=" + type + ", baseUrl=" + baseUrl + "]";
    }
}
