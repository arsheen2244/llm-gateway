package dev.llmgateway.gateway.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one HTTP client used to call providers. Sharing it means sharing one
 * connection pool, so repeated calls to the same provider reuse open
 * connections instead of doing a new TCP and TLS handshake every time.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    RestClient providerRestClient(RestClient.Builder builder, GatewayProperties properties) {
        HttpClient jdkClient = HttpClient.newBuilder()
                // HTTP/1.1 keeps streaming behaviour simple and predictable;
                // HTTP/2 multiplexing adds nothing for a few long-lived streams.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.timeouts().connect())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
        factory.setReadTimeout(properties.timeouts().read());
        return builder.requestFactory(factory).build();
    }
}
