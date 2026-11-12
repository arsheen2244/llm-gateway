package dev.llmgateway.gateway.streaming;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes an SSE response to the client, one flushed frame at a time.
 *
 * <p><b>Lazy commit:</b> nothing is sent, not even the status line, until
 * the first frame. Until {@link #started()} is true, the caller can still
 * give up and send a normal HTTP error (such as a 502 with a JSON body)
 * instead of a stream.
 *
 * <p>Not thread-safe: one request thread writes to it.
 */
public class SseResponse {

    private final HttpServletResponse response;
    private final ObjectMapper json;
    private OutputStream out;
    private boolean started;

    public SseResponse(HttpServletResponse response, ObjectMapper json) {
        this.response = response;
        this.json = json;
    }

    public boolean started() {
        return started;
    }

    /** {@code data: <payload as JSON>} */
    public void send(Object payload) {
        try {
            write("data: " + json.writeValueAsString(payload) + "\n\n");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize SSE payload", e);
        }
    }

    /** OpenAI's end-of-stream marker. */
    public void done() {
        write("data: [DONE]\n\n");
    }

    private void write(String frame) {
        try {
            if (!started) {
                start();
            }
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush(); // send now: buffering would defeat the point of streaming
        } catch (IOException e) {
            throw new ClientDisconnectedException(e);
        }
    }

    private void start() throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        // Tells nginx-style reverse proxies not to buffer the stream.
        response.setHeader("X-Accel-Buffering", "no");
        out = response.getOutputStream();
        started = true;
    }
}
