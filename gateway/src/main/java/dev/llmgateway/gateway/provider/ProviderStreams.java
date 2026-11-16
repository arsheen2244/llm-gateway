package dev.llmgateway.gateway.provider;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

import dev.llmgateway.gateway.provider.ProviderException.Reason;
import org.apache.hc.core5.http.io.EofSensorInputStream;

/**
 * Stream-reading logic shared by all adapters: read SSE events, release the
 * provider's connection correctly, and decide when a stream counts as broken.
 */
public final class ProviderStreams {

    private ProviderStreams() {
    }

    /**
     * Reads {@code body} to the end, passing each event to {@code listener}.
     *
     * <p>If reading stops early (the listener threw because the client went
     * away, or the stream broke), the connection is <b>aborted</b>, not just closed.
     * Apache HttpClient's normal close reads the rest of the body so the connection
     * can be reused, which would mean receiving (and paying for) every remaining
     * token after the client has left.
     *
     * @param completed reports whether the provider's own end-of-stream marker was
     *                  seen. If the stream ends without it, the stream was cut short
     * @throws IOException       if the connection failed before any event arrived
     *                           (an ordinary I/O failure, same as for non-streaming calls)
     * @throws ProviderException STREAM_INTERRUPTED if it broke after starting or ended early
     */
    public static void consume(String provider, InputStream body, SseEventReader.Listener listener,
                               BooleanSupplier completed) throws IOException {
        int[] events = {0}; // array so the lambda below can update it
        boolean readToEnd = false;
        try {
            SseEventReader.read(body, (event, data) -> {
                events[0]++;
                listener.onEvent(event, data);
            });
            readToEnd = true;
        } catch (IOException e) {
            if (events[0] == 0) {
                throw e;
            }
            throw ProviderException.of(provider, Reason.STREAM_INTERRUPTED,
                    "Stream broke after it started: " + e.getMessage(), e);
        } finally {
            if (!readToEnd) {
                abort(body);
            }
            closeQuietly(body);
        }
        if (!completed.getAsBoolean()) {
            throw ProviderException.of(provider, Reason.STREAM_INTERRUPTED,
                    "Stream ended before the provider's end-of-stream marker", null);
        }
    }

    /** Drops the underlying connection instead of draining it. */
    private static void abort(InputStream body) {
        if (body instanceof EofSensorInputStream apacheBody) {
            try {
                apacheBody.abort();
            } catch (IOException ignored) {
                // The connection is being thrown away anyway.
            }
        }
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // Nothing useful to do: the stream is finished either way.
        }
    }
}
