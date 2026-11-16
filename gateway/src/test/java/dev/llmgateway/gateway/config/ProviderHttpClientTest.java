package dev.llmgateway.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import dev.llmgateway.gateway.config.GatewayProperties.Provider;
import dev.llmgateway.gateway.config.GatewayProperties.Route;
import dev.llmgateway.gateway.config.GatewayProperties.Timeouts;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.openai.OpenAiAdapter;
import dev.llmgateway.gateway.routing.ProviderConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Pins down the HTTP client behaviours the gateway depends on (design notes 11 and 16),
 * using the real client configuration and adapter against a deliberately slow local server.
 * If the client library is ever swapped, these tests say whether the new one still behaves.
 */
class ProviderHttpClientTest {

    static {
        // The fake server must not add a Nagle delay of its own: the JDK's HttpServer writes
        // response headers and body separately with Nagle ON, which on Linux costs ~40 ms per
        // request (delayed ACK) and made the Nagle test measure the server, not our client.
        // Read once when the JDK server first starts, so it is set before any test runs.
        System.setProperty("sun.net.httpserver.nodelay", "true");
    }

    private static final String CHUNK = "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}\n\n";
    private static final ChatCompletionRequest REQUEST = new ChatCompletionRequest("m",
            List.of(new ChatMessage("user", "hi")), true, null, null, null, null, null);

    private HttpServer server;
    private CloseableHttpClient httpClient;
    private OpenAiAdapter adapter;
    private ProviderConfig provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/steady/v1/chat/completions", ex -> stream(ex, 8, 300));   // 2.4 s, never silent > 0.3 s
        server.createContext("/stall/v1/chat/completions", ex -> stream(ex, 2, 3000));   // 3 s of silence
        server.createContext("/long/v1/chat/completions", ex -> stream(ex, 20, 150));    // 3 s total
        server.createContext("/fast/v1/chat/completions", ProviderHttpClientTest::instantCompletion);
        server.start();

        // Read timeout 1 s: shorter than the steady stream's total, longer than its gaps.
        GatewayProperties properties = new GatewayProperties(
                Map.of("p", new Provider("openai", "http://unused", "k")),
                List.of(new Route("*", "p")),
                new Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(1)));
        HttpClientConfig config = new HttpClientConfig();
        httpClient = config.providerHttpClient(properties);
        RestClient restClient = config.providerRestClient(RestClient.builder(), httpClient);
        adapter = new OpenAiAdapter(restClient, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        httpClient.close();
        server.stop(0);
    }

    @Test
    void longButSteadyStreamIsNotCutOffByTheReadTimeout() {
        List<ChatCompletionChunk> chunks = new ArrayList<>();

        adapter.stream(at("/steady"), REQUEST, chunks::add);

        assertThat(chunks).hasSize(8);
    }

    @Test
    void stalledStreamIsCutOffByTheReadTimeout() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> adapter.stream(at("/stall"), REQUEST, chunk -> { }))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProviderException.Reason.STREAM_INTERRUPTED));
        assertThat(elapsedMs(start)).isLessThan(2_500);
    }

    @Test
    void clientDisconnectAbortsTheProviderStreamInsteadOfDrainingIt() {
        RuntimeException clientGone = new RuntimeException("client gone");
        long start = System.nanoTime();

        assertThatThrownBy(() -> adapter.stream(at("/long"), REQUEST, chunk -> { throw clientGone; }))
                .isSameAs(clientGone);
        // Draining the remaining 19 chunks would take about 3 s.
        assertThat(elapsedMs(start)).isLessThan(1_000);
    }

    @Test
    void smallRequestsAreNotDelayedByNagle() {
        ChatCompletionRequest request = REQUEST.withStream(false, null);
        long[] times = new long[30];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            adapter.complete(at("/fast"), request);
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);

        // With Nagle on, separate header/body writes cost ~16 ms per call on Windows
        // (40 ms on Linux). With TCP_NODELAY a local call takes about 1 ms.
        assertThat(times[times.length / 2] / 1_000_000).isLessThan(10);
    }

    private ProviderConfig at(String path) {
        return new ProviderConfig("p", "openai", "http://127.0.0.1:" + server.getAddress().getPort() + path, "k");
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static void stream(HttpExchange exchange, int chunks, long gapMs) {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); // 0 = chunked body
            OutputStream out = exchange.getResponseBody();
            for (int i = 0; i < chunks; i++) {
                out.write(CHUNK.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(gapMs);
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException expected) {
            // The client hung up (the abort and timeout tests do this on purpose).
        }
    }

    private static void instantCompletion(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
