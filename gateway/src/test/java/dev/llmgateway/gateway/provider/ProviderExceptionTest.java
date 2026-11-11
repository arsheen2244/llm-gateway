package dev.llmgateway.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import org.junit.jupiter.api.Test;

class ProviderExceptionTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsMessageFromJsonErrorBody() {
        byte[] body = "{\"error\":{\"message\":\"Bad thing\",\"type\":\"x\"}}".getBytes(StandardCharsets.UTF_8);

        ProviderException e = UpstreamErrors.fromErrorResponse("p", 500, body, json);

        assertThat(e.getMessage()).isEqualTo("Bad thing");
        assertThat(e.upstreamStatus()).isEqualTo(500);
    }

    @Test
    void fallsBackToShortTextWhenBodyIsNotJson() {
        byte[] body = "Bad Gateway".getBytes(StandardCharsets.UTF_8);

        assertThat(UpstreamErrors.fromErrorResponse("p", 502, body, json).getMessage()).isEqualTo("Bad Gateway");
    }

    @Test
    void detectsTimeoutAnywhereInTheCauseChain() {
        Exception wrapped = new RuntimeException(new IOException(new HttpTimeoutException("slow")));

        assertThat(UpstreamErrors.fromIoFailure("p", wrapped).reason()).isEqualTo(Reason.TIMEOUT);
    }

    @Test
    void connectionRefusedIsIoError() {
        Exception wrapped = new RuntimeException(new ConnectException("refused"));

        assertThat(UpstreamErrors.fromIoFailure("p", wrapped).reason()).isEqualTo(Reason.IO_ERROR);
    }

    @Test
    void retryableOnlyForTransientFailures() {
        assertThat(ProviderException.errorResponse("p", 503, "").isRetryable()).isTrue();
        assertThat(ProviderException.errorResponse("p", 429, "").isRetryable()).isTrue();
        assertThat(ProviderException.errorResponse("p", 400, "").isRetryable()).isFalse();
        assertThat(ProviderException.of("p", Reason.TIMEOUT, "", null).isRetryable()).isTrue();
        assertThat(ProviderException.of("p", Reason.STREAM_INTERRUPTED, "", null).isRetryable()).isFalse();
    }
}
