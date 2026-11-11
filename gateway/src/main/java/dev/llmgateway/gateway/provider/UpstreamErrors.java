package dev.llmgateway.gateway.provider;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.provider.ProviderException.Reason;

/** Turns raw failures from provider calls into {@link ProviderException}s. Shared by every adapter. */
public final class UpstreamErrors {

    private UpstreamErrors() {
    }

    /**
     * An HTTP error response. Both OpenAI and Anthropic put a readable message at
     * {@code error.message}. If the body is not JSON we fall back to a short text.
     */
    public static ProviderException fromErrorResponse(String provider, int status, byte[] body, ObjectMapper json) {
        String message = "HTTP " + status;
        try {
            JsonNode node = json.readTree(body);
            String upstreamMessage = node == null ? "" : node.path("error").path("message").asText("");
            if (!upstreamMessage.isBlank()) {
                message = upstreamMessage;
            }
        } catch (IOException notJson) {
            String text = new String(body, StandardCharsets.UTF_8).strip();
            if (!text.isEmpty() && text.length() < 200) {
                message = text;
            }
        }
        return ProviderException.errorResponse(provider, status, message);
    }

    /** A failure with no usable HTTP response: decide between timeout and plain I/O error. */
    public static ProviderException fromIoFailure(String provider, Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return ProviderException.of(provider, Reason.TIMEOUT, "Provider did not respond in time", failure);
            }
            if (t instanceof ConnectException) {
                return ProviderException.of(provider, Reason.IO_ERROR, "Could not connect to provider", failure);
            }
        }
        return ProviderException.of(provider, Reason.IO_ERROR, "I/O error talking to provider", failure);
    }
}
