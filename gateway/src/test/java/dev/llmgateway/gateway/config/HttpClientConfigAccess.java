package dev.llmgateway.gateway.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.web.client.RestClient;

/**
 * Lets tests in other packages build the real provider HTTP client exactly as production
 * does ({@link HttpClientConfig}'s bean methods are package-private).
 */
public final class HttpClientConfigAccess {

    public record Clients(CloseableHttpClient httpClient, RestClient restClient) {
    }

    private HttpClientConfigAccess() {
    }

    public static Clients build(GatewayProperties properties) {
        HttpClientConfig config = new HttpClientConfig();
        CloseableHttpClient httpClient = config.providerHttpClient(properties);
        return new Clients(httpClient, config.providerRestClient(RestClient.builder(), httpClient));
    }
}
