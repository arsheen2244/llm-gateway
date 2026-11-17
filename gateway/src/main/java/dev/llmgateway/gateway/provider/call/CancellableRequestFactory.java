package dev.llmgateway.gateway.provider.call;

import java.net.URI;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Spring's Apache request factory, plus one thing: each Apache request it creates is bound
 * to the current {@link CallControl}, so that call can be aborted from another thread.
 * Spring's {@code RestClient} doesn't expose the underlying request, and this protected
 * hook is the point where it is created.
 */
public class CancellableRequestFactory extends HttpComponentsClientHttpRequestFactory {

    public CancellableRequestFactory(HttpClient httpClient) {
        super(httpClient);
    }

    @Override
    protected ClassicHttpRequest createHttpUriRequest(HttpMethod httpMethod, URI uri) {
        ClassicHttpRequest request = super.createHttpUriRequest(httpMethod, uri);
        CallControl control = CallControl.current();
        if (control != null && request instanceof Cancellable cancellable) {
            control.bind(cancellable);
        }
        return request;
    }
}
