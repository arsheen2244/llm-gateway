package dev.llmgateway.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one HTTP client used to call providers.
 *
 * <p>It is built on {@code HttpURLConnection} (Spring's "simple" factory)
 * because its read timeout is the maximum <em>silence between bytes</em>.
 * A long, healthy stream is never cut off, but a stalled one is. The JDK
 * HttpClient-based factory applies its read timeout to the whole response
 * instead, which would kill any stream longer than the timeout. That was
 * measured, not assumed (design note 11).
 *
 * <p>HttpURLConnection keeps idle connections in the JDK's keep-alive cache,
 * so repeated calls to a provider reuse open connections.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    RestClient providerRestClient(RestClient.Builder builder, GatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeouts().connect());
        factory.setReadTimeout(properties.timeouts().read());
        return builder.requestFactory(factory).build();
    }
}
