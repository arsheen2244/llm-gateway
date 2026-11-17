package dev.llmgateway.gateway.provider.call;

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Marks the moment a provider's response headers arrive. {@code execute} returns exactly
 * then (the body is read afterwards), so this is where the first-byte deadline is disarmed.
 */
public class FirstByteInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        CallControl control = CallControl.current();
        if (control != null) {
            control.firstByteReceived();
        }
        return response;
    }
}
