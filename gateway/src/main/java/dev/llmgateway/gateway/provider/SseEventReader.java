package dev.llmgateway.gateway.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Parses a Server-Sent Events stream (the format both OpenAI and Anthropic stream in).
 *
 * <p>The format is plain text. Each event is a group of {@code field: value}
 * lines ended by a blank line. We use two fields: {@code event} (the event's
 * name, used by Anthropic) and {@code data} (the payload). Lines starting with
 * ":" are comments, often used as keep-alives. An event that is not finished
 * when the stream ends is dropped, as the SSE spec says.
 */
public final class SseEventReader {

    /** Called once per complete event. {@code event} is null when the stream doesn't name events. */
    @FunctionalInterface
    public interface Listener {
        void onEvent(String event, String data);
    }

    private SseEventReader() {
    }

    /** Reads until the stream ends. Blocks while waiting for data. */
    public static void read(InputStream in, Listener listener) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String event = null;
        StringBuilder data = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data != null) {
                    listener.onEvent(event, data.toString());
                }
                event = null;
                data = null;
            } else if (!line.startsWith(":")) {
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (field.equals("event")) {
                    event = value;
                } else if (field.equals("data")) {
                    data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
                }
            }
        }
    }
}
