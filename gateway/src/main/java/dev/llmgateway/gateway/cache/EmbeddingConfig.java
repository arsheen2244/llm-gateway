package dev.llmgateway.gateway.cache;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The embedding client, only when the cache and its semantic layer are both on.
 *
 * <p>It gets its own small HTTP client rather than the providers' one: the timeouts are very
 * different (half a second here, a minute for a provider), and a stuck embedding service must
 * not use up connections the provider calls need. Same Apache client and TCP_NODELAY setting
 * as the providers (design note 16).
 */
@Configuration
@ConditionalOnExpression("${gateway.cache.enabled:true} and ${gateway.cache.semantic-enabled:true}")
public class EmbeddingConfig {

    static final int MAX_CONNECTIONS = 50;

    @Bean(destroyMethod = "close")
    CloseableHttpClient embeddingHttpClient(EmbeddingProperties properties) {
        Timeout timeout = Timeout.of(properties.timeout());
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultSocketConfig(SocketConfig.custom().setTcpNoDelay(true).setSoTimeout(timeout).build())
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(timeout).setSocketTimeout(timeout).build())
                        .setMaxConnTotal(MAX_CONNECTIONS)
                        .setMaxConnPerRoute(MAX_CONNECTIONS)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(timeout).build())
                .disableAutomaticRetries()
                .build();
    }

    @Bean
    Embedder embedder(EmbeddingProperties properties, CloseableHttpClient embeddingHttpClient) {
        RestClient http = RestClient.builder()
                .baseUrl(properties.url().toString())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(embeddingHttpClient))
                .build();
        return new TeiEmbedder(http, properties.dimensions());
    }
}
