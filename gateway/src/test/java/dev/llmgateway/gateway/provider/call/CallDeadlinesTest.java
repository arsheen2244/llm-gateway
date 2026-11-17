package dev.llmgateway.gateway.provider.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import dev.llmgateway.gateway.config.GatewayProperties;
import dev.llmgateway.gateway.config.GatewayProperties.Provider;
import dev.llmgateway.gateway.config.GatewayProperties.Route;
import dev.llmgateway.gateway.config.GatewayProperties.Timeouts;
import dev.llmgateway.gateway.config.HttpClientConfigAccess;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import dev.llmgateway.gateway.provider.openai.OpenAiAdapter;
import dev.llmgateway.gateway.routing.ProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The first-byte, total and stream deadlines, and cancelling a call from another thread
 * (design note 26), against a deliberately slow local server and the real HTTP client.
 *
 * <p>Deadlines here: first byte 500 ms, total 1 s, max stream 1.5 s; idle (read) 2 s, so
 * the idle timeout never fires first.
 */
class CallDeadlinesTest {

    static {
        System.setProperty("sun.net.httpserver.nodelay", "true"); // see ProviderHttpClientTest
    }

    private static final String CHUNK = "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}\n\n";
    private static final String COMPLETION =
            "{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[]}";
    private static final ChatCompletionRequest REQUEST = new ChatCompletionRequest("m",
            List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);

    private HttpServer server;
    private HttpClientConfigAccess.Clients clients;
    private OpenAiAdapter adapter;
    private ProviderCaller caller;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/no-headers/v1/chat/completions", ex -> delayedCompletion(ex, 3_000));
        server.createContext("/fast/v1/chat/completions", ex -> delayedCompletion(ex, 0));
        server.createContext("/slow-body/v1/chat/completions", CallDeadlinesTest::slowBody);
        server.createContext("/stream-1200/v1/chat/completions", ex -> stream(ex, 4, 300));  // 1.2 s
        server.createContext("/stream-3000/v1/chat/completions", ex -> stream(ex, 10, 300)); // 3 s
        server.start();

        Timeouts timeouts = new Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(1500));
        GatewayProperties properties = new GatewayProperties(
                Map.of("p", new Provider("openai", "http://unused", "k")), List.of(Route.single("*", "p")), timeouts);
        clients = HttpClientConfigAccess.build(properties);
        adapter = new OpenAiAdapter(clients.restClient(), new ObjectMapper());
        caller = new ProviderCaller(properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        caller.close();
        clients.httpClient().close();
        server.stop(0);
    }

    @Test
    void noResponseHeadersWithinFirstByteDeadlineIsATimeout() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> caller.complete(adapter, at("/no-headers"), REQUEST, new CallControl()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
                    assertThat(e.getMessage()).contains("No response from provider within 500 ms");
                });
        assertThat(elapsedMs(start)).isLessThan(1_500); // the server would have waited 3 s
    }

    @Test
    void fastCallIsUnaffected() {
        assertThat(caller.complete(adapter, at("/fast"), REQUEST, new CallControl()).id()).isEqualTo("x");
    }

    @Test
    void slowBodyAfterHeadersHitsTheTotalDeadline() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> caller.complete(adapter, at("/slow-body"), REQUEST, new CallControl()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
                    assertThat(e.getMessage()).contains("exceeded 1000 ms");
                });
        assertThat(elapsedMs(start)).isLessThan(2_000); // the body would take 3 s
    }

    @Test
    void streamsLongerThanTheTotalDeadlineAreNotCutOff() {
        List<ChatCompletionChunk> chunks = new ArrayList<>();

        // 1.2 s: longer than "total" (1 s) but within the stream maximum (1.5 s).
        caller.stream(adapter, at("/stream-1200"), REQUEST.withStream(true, null), chunks::add, new CallControl());

        assertThat(chunks).hasSize(4);
    }

    @Test
    void streamsLongerThanTheMaximumDurationAreInterrupted() {
        List<ChatCompletionChunk> chunks = new ArrayList<>();

        assertThatThrownBy(() -> caller.stream(adapter, at("/stream-3000"), REQUEST.withStream(true, null),
                chunks::add, new CallControl()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.STREAM_INTERRUPTED);
                    assertThat(e.getMessage()).contains("maximum duration");
                });
        assertThat(chunks).isNotEmpty().hasSizeLessThan(10);
    }

    @Test
    void cancellingFromAnotherThreadAbortsTheCallPromptly() throws Exception {
        CallControl control = new CallControl();
        long start = System.nanoTime();
        CompletableFuture<Void> call = CompletableFuture.runAsync(() ->
                caller.stream(adapter, at("/stream-3000"), REQUEST.withStream(true, null), chunk -> { }, control));

        Thread.sleep(200);
        assertThat(control.stop(CallControl.Stop.CANCELLED)).isTrue();

        assertThatThrownBy(() -> call.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(ProviderException.class);
        assertThat(elapsedMs(start)).isLessThan(1_000);
        assertThat(control.stopReason()).isEqualTo(CallControl.Stop.CANCELLED);
    }

    @Test
    void onlyTheFirstStopReasonCounts() {
        CallControl control = new CallControl();

        assertThat(control.stop(CallControl.Stop.CANCELLED)).isTrue();
        assertThat(control.stop(CallControl.Stop.TOTAL_TIMEOUT)).isFalse();
        assertThat(control.stopReason()).isEqualTo(CallControl.Stop.CANCELLED);
    }

    // ---- helpers ----

    private ProviderConfig at(String path) {
        return new ProviderConfig("p", "openai", "http://127.0.0.1:" + server.getAddress().getPort() + path, "k");
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static void delayedCompletion(HttpExchange exchange, long delayMs) {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            Thread.sleep(delayMs);
            byte[] body = COMPLETION.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException | InterruptedException expected) {
            // the client gave up first
        }
    }

    /** Headers at once, then the JSON body trickles out over 3 s (gaps of 300 ms < idle 2 s). */
    private static void slowBody(HttpExchange exchange) {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            byte[] body = COMPLETION.getBytes(StandardCharsets.UTF_8);
            int step = Math.max(1, body.length / 10);
            for (int i = 0; i < body.length; i += step) {
                out.write(body, i, Math.min(step, body.length - i));
                out.flush();
                Thread.sleep(300);
            }
        } catch (IOException | InterruptedException expected) {
            // the client gave up first
        }
    }

    private static void stream(HttpExchange exchange, int chunks, long gapMs) {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (int i = 0; i < chunks; i++) {
                out.write(CHUNK.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(gapMs);
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException expected) {
            // the client gave up first
        }
    }
}
