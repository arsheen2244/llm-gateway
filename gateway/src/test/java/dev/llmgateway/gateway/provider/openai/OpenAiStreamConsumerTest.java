package dev.llmgateway.gateway.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import org.junit.jupiter.api.Test;

/** Unit tests for how the OpenAI adapter reads a stream, using in-memory streams. */
class OpenAiStreamConsumerTest {

    private static final String CHUNK =
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n";

    private final OpenAiAdapter adapter = new OpenAiAdapter(null, new ObjectMapper());
    private final List<ChatCompletionChunk> received = new ArrayList<>();

    @Test
    void deliversChunksUntilDone() throws IOException {
        adapter.consumeStream("p", stream(CHUNK + CHUNK + "data: [DONE]\n\n"), received::add);

        assertThat(received).hasSize(2);
        assertThat(received.get(0).choices().get(0).delta().content()).isEqualTo("Hi");
    }

    @Test
    void streamEndingWithoutDoneIsInterrupted() {
        assertThatThrownBy(() -> adapter.consumeStream("p", stream(CHUNK), received::add))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.STREAM_INTERRUPTED));
        assertThat(received).hasSize(1);
    }

    @Test
    void errorEventInsideStreamIsInterruptedWithProviderMessage() {
        String body = CHUNK + "data: {\"error\":{\"message\":\"server overloaded\"}}\n\n";

        assertThatThrownBy(() -> adapter.consumeStream("p", stream(body), received::add))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.STREAM_INTERRUPTED);
                    assertThat(e.getMessage()).isEqualTo("server overloaded");
                });
    }

    @Test
    void ioFailureAfterFirstEventIsInterrupted() {
        InputStream body = new SequenceInputStream(stream(CHUNK), new FailingInputStream());

        assertThatThrownBy(() -> adapter.consumeStream("p", body, received::add))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.STREAM_INTERRUPTED));
    }

    @Test
    void ioFailureBeforeAnyEventIsPlainIoException() {
        assertThatThrownBy(() -> adapter.consumeStream("p", new FailingInputStream(), received::add))
                .isInstanceOf(IOException.class);
    }

    @Test
    void closesProviderStreamWhenClientGoesAway() {
        TrackingInputStream body = new TrackingInputStream(CHUNK + CHUNK + "data: [DONE]\n\n");
        RuntimeException clientGone = new RuntimeException("client gone");

        assertThatThrownBy(() -> adapter.consumeStream("p", body, chunk -> { throw clientGone; }))
                .isSameAs(clientGone);
        assertThat(body.closed).isTrue();
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static class FailingInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("connection reset");
        }
    }

    private static class TrackingInputStream extends ByteArrayInputStream {
        boolean closed;

        TrackingInputStream(String text) {
            super(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
