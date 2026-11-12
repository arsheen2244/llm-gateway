package dev.llmgateway.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SseEventReaderTest {

    @Test
    void readsUnnamedDataEvents() throws IOException {
        assertThat(read("data: {\"a\":1}\n\ndata: [DONE]\n\n"))
                .containsExactly("null|{\"a\":1}", "null|[DONE]");
    }

    @Test
    void readsNamedEvents() throws IOException {
        assertThat(read("event: message_start\ndata: {}\n\nevent: ping\ndata: {}\n\n"))
                .containsExactly("message_start|{}", "ping|{}");
    }

    @Test
    void joinsMultipleDataLinesWithNewline() throws IOException {
        assertThat(read("data: line1\ndata: line2\n\n")).containsExactly("null|line1\nline2");
    }

    @Test
    void ignoresCommentsAndUnknownFields() throws IOException {
        assertThat(read(": keep-alive\nid: 7\ndata: x\n\n")).containsExactly("null|x");
    }

    @Test
    void handlesWindowsLineEndingsAndMissingSpaceAfterColon() throws IOException {
        assertThat(read("data:x\r\n\r\n")).containsExactly("null|x");
    }

    @Test
    void dropsUnfinishedEventAtEndOfStream() throws IOException {
        assertThat(read("data: complete\n\ndata: cut-off")).containsExactly("null|complete");
    }

    private static List<String> read(String stream) throws IOException {
        List<String> events = new ArrayList<>();
        SseEventReader.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                (event, data) -> events.add(event + "|" + data));
        return events;
    }
}
