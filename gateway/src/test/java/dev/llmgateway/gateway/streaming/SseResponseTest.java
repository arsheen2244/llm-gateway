package dev.llmgateway.gateway.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.pipeline.ResponseHeaders;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class SseResponseTest {

    private final ObjectMapper json = new ObjectMapper();
    private final MockHttpServletResponse servlet = new MockHttpServletResponse();
    private final SseResponse sse = new SseResponse(servlet, json, new ResponseHeaders());

    @Test
    void nothingIsCommittedBeforeTheFirstFrame() {
        assertThat(sse.started()).isFalse();
        assertThat(servlet.getContentType()).isNull();
    }

    @Test
    void firstFrameSetsStreamingHeaders() {
        sse.send(Map.of("a", 1));

        assertThat(sse.started()).isTrue();
        assertThat(servlet.getStatus()).isEqualTo(200);
        assertThat(servlet.getContentType()).startsWith("text/event-stream");
        assertThat(servlet.getHeader("Cache-Control")).isEqualTo("no-cache");
    }

    @Test
    void writesDataFramesAndDoneMarker() throws Exception {
        sse.send(Map.of("a", 1));
        sse.done();

        assertThat(servlet.getContentAsString()).isEqualTo("data: {\"a\":1}\n\ndata: [DONE]\n\n");
    }

    @Test
    void writeFailureBecomesClientDisconnected() {
        HttpServletResponseWrapper broken = new HttpServletResponseWrapper(new MockHttpServletResponse()) {
            @Override
            public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        throw new IOException("Broken pipe");
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                    }
                };
            }
        };

        assertThatThrownBy(() -> new SseResponse(broken, json, new ResponseHeaders()).done())
                .isInstanceOf(ClientDisconnectedException.class);
    }
}
