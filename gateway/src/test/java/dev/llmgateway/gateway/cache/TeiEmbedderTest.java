package dev.llmgateway.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The embedding client against a fake text-embeddings-inference server. */
class TeiEmbedderTest {

    private static final int DIMENSIONS = 3;

    private HttpServer server;
    private CloseableHttpClient httpClient;
    private Embedder embedder;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "[[0.6,0.8,0.0]]";
    private volatile long delayMs;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed", this::handle);
        server.start();
        EmbeddingProperties properties = new EmbeddingProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofMillis(300), DIMENSIONS);
        EmbeddingConfig config = new EmbeddingConfig();
        httpClient = config.embeddingHttpClient(properties);
        embedder = config.embedder(properties, httpClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        httpClient.close();
        server.stop(0);
    }

    @Test
    void returnsTheVectorAndAsksForNormalizedUntruncatedEmbeddings() {
        float[] vector = embedder.embed("What is Redis?");

        assertThat(vector).containsExactly(0.6f, 0.8f, 0.0f);
        assertThat(lastBody.get()).contains("\"inputs\":\"What is Redis?\"")
                .contains("\"normalize\":true").contains("\"truncate\":false");
    }

    @Test
    void errorStatusIsAnEmbeddingException() {
        status = 413; // TEI's answer to an input longer than the model reads
        answer = "{\"error\":\"Input validation error\"}";

        assertThatThrownBy(() -> embedder.embed("very long"))
                .isInstanceOf(EmbeddingException.class);
    }

    @Test
    void wrongVectorLengthIsAnEmbeddingException() {
        answer = "[[0.1,0.2]]";

        assertThatThrownBy(() -> embedder.embed("hi"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("3 numbers");
    }

    @Test
    void slowServiceTimesOutQuickly() {
        delayMs = 2_000;
        long start = System.nanoTime();

        assertThatThrownBy(() -> embedder.embed("hi")).isInstanceOf(EmbeddingException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    void unreachableServiceIsAnEmbeddingException() {
        server.stop(0);

        assertThatThrownBy(() -> embedder.embed("hi")).isInstanceOf(EmbeddingException.class);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException ignored) {
            // the client gave up (timeout test)
        }
    }
}
