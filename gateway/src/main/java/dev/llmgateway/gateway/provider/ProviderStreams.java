package dev.llmgateway.gateway.provider;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

import dev.llmgateway.gateway.provider.ProviderException.Reason;

/**
 * Stream-reading logic shared by all adapters: read SSE events, always close
 * the provider's body, and decide when a stream counts as broken.
 */
public final class ProviderStreams {

    private ProviderStreams() {
    }

    /**
     * Reads {@code body} to the end, passing each event to {@code listener}.
     *
     * <p>The body is always closed on the way out. Closing it before the stream
     * has ended makes HttpURLConnection drop the socket, so if the client has gone
     * away (the listener throws) we stop receiving tokens nobody will read.
     *
     * @param completed reports whether the provider's own end-of-stream marker was
     *                  seen. If the stream ends without it, the stream was cut short
     * @throws IOException      if the connection failed before any event arrived
     *                          (an ordinary I/O failure, same as for non-streaming calls)
     * @throws ProviderException STREAM_INTERRUPTED if it broke after starting or ended early
     */
    public static void consume(String provider, InputStream body, SseEventReader.Listener listener,
                               BooleanSupplier completed) throws IOException {
        int[] events = {0}; // array so the lambda below can update it
        try (body) {
            SseEventReader.read(body, (event, data) -> {
                events[0]++;
                listener.onEvent(event, data);
            });
        } catch (IOException e) {
            if (events[0] == 0) {
                throw e;
            }
            throw ProviderException.of(provider, Reason.STREAM_INTERRUPTED,
                    "Stream broke after it started: " + e.getMessage(), e);
        }
        if (!completed.getAsBoolean()) {
            throw ProviderException.of(provider, Reason.STREAM_INTERRUPTED,
                    "Stream ended before the provider's end-of-stream marker", null);
        }
    }
}
