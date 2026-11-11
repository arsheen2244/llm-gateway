package dev.llmgateway.mock.sse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes Server-Sent Events frames. A frame is one or more "field: value" lines
 * followed by a blank line. Each frame is flushed immediately so the client
 * receives it without waiting for a buffer to fill.
 */
public class SseWriter {

    private final OutputStream out;
    private final ObjectMapper json;

    public SseWriter(OutputStream out, ObjectMapper json) {
        this.out = out;
        this.json = json;
    }

    /** {@code data: <payload as JSON>} */
    public void data(Object payload) throws IOException {
        write("data: " + json.writeValueAsString(payload) + "\n\n");
    }

    /** {@code data: <raw text>}, used for OpenAI's {@code [DONE]} marker. */
    public void rawData(String text) throws IOException {
        write("data: " + text + "\n\n");
    }

    /** {@code event: <name>} plus {@code data: <payload>}, the style Anthropic uses. */
    public void event(String name, Object payload) throws IOException {
        write("event: " + name + "\ndata: " + json.writeValueAsString(payload) + "\n\n");
    }

    private void write(String frame) throws IOException {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
