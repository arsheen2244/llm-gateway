package dev.llmgateway.gateway.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one HTTP client used to call providers: Apache HttpClient 5 with a shared
 * connection pool (design note 16).
 *
 * <p>Three settings matter:
 * <ul>
 *   <li><b>TCP_NODELAY on:</b> turns off Nagle's algorithm, which otherwise holds
 *       back small packets. With Nagle on, sending the request headers and body as
 *       separate writes cost about 16 ms per call (measured).</li>
 *   <li><b>Socket timeout = {@code timeouts.read}:</b> the longest allowed silence
 *       between bytes. A long, healthy stream is never cut off; a stalled one is.</li>
 *   <li><b>Automatic retries off:</b> retrying is a policy decision (week 3), not
 *       something the HTTP library should do silently.</li>
 * </ul>
 */
@Configuration
public class HttpClientConfig {

    /** Upper bounds, not pre-opened connections. Sized for one gateway instance in week 1. */
    static final int MAX_CONNECTIONS_TOTAL = 500;
    static final int MAX_CONNECTIONS_PER_PROVIDER = 200;

    @Bean(destroyMethod = "close")
    CloseableHttpClient providerHttpClient(GatewayProperties properties) {
        GatewayProperties.Timeouts timeouts = properties.timeouts();
        Timeout connect = Timeout.of(timeouts.connect());
        Timeout read = Timeout.of(timeouts.read());

        PoolingHttpClientConnectionManager pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultSocketConfig(SocketConfig.custom()
                        .setTcpNoDelay(true)
                        .setSoTimeout(read)
                        .build())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(connect)
                        .setSocketTimeout(read)
                        .build())
                .setMaxConnTotal(MAX_CONNECTIONS_TOTAL)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_PROVIDER)
                .build();

        return HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(RequestConfig.custom()
                        // How long to wait for a free pooled connection before failing.
                        .setConnectionRequestTimeout(connect)
                        .build())
                .disableAutomaticRetries()
                .build();
    }

    @Bean
    RestClient providerRestClient(RestClient.Builder builder, CloseableHttpClient providerHttpClient) {
        return builder.requestFactory(new HttpComponentsClientHttpRequestFactory(providerHttpClient)).build();
    }
}
