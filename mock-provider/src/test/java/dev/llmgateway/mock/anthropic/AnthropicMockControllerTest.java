package dev.llmgateway.mock.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mock.chunk-count=3", "mock.chunk-delay-ms=0", "mock.latency-ms=0"})
class AnthropicMockControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    void normalRequestReturnsMessageWithTextBlockAndUsage() throws Exception {
        HttpResponse<String> response = post(body("mock-claude", true, false), true, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode message = json.readTree(response.body());
        assertThat(message.at("/type").asText()).isEqualTo("message");
        assertThat(message.at("/content/0/text").asText()).isEqualTo("This is a");
        assertThat(message.at("/stop_reason").asText()).isEqualTo("end_turn");
        assertThat(message.at("/usage/output_tokens").asInt()).isEqualTo(3);
    }

    @Test
    void streamingRequestSendsAnthropicEventSequence() throws Exception {
        HttpResponse<Stream<String>> response =
                post(body("mock-claude", true, true), true, HttpResponse.BodyHandlers.ofLines());

        List<String> events = response.body()
                .filter(line -> line.startsWith("event: "))
                .map(line -> line.substring("event: ".length()))
                .toList();

        assertThat(events).containsExactly(
                "message_start", "content_block_start", "ping",
                "content_block_delta", "content_block_delta", "content_block_delta",
                "content_block_stop", "message_delta", "message_stop");
    }

    @Test
    void missingApiKeyIsRejected() throws Exception {
        HttpResponse<String> response = post(body("mock-claude", true, false), false, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).at("/error/type").asText()).isEqualTo("authentication_error");
    }

    @Test
    void missingMaxTokensIsRejected() throws Exception {
        HttpResponse<String> response = post(body("mock-claude", false, false), true, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void forcedOverloadReturns529() throws Exception {
        HttpResponse<String> response =
                post(body("mock-claude-error-529", true, false), true, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(529);
        assertThat(json.readTree(response.body()).at("/error/type").asText()).isEqualTo("overloaded_error");
    }

    @Test
    void forcedMidStreamFailureDropsConnection() throws Exception {
        HttpResponse<Stream<String>> response =
                post(body("mock-claude-fail-after-1", true, true), true, HttpResponse.BodyHandlers.ofLines());

        List<String> received = new ArrayList<>();
        try {
            response.body().forEach(received::add);
        } catch (UncheckedIOException expected) {
            // The usual outcome of an aborted connection.
        }

        // At most the one delta that was sent (a TCP reset may discard unread data), and
        // never the end-of-message event.
        assertThat(received).filteredOn(line -> line.equals("event: content_block_delta")).hasSizeLessThanOrEqualTo(1);
        assertThat(received).doesNotContain("event: message_stop");
    }

    private <T> HttpResponse<T> post(String body, boolean withKey, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (withKey) {
            request.header("x-api-key", "test-key");
        }
        return http.send(request.build(), handler);
    }

    private static String body(String model, boolean withMaxTokens, boolean stream) {
        String maxTokens = withMaxTokens ? "\"max_tokens\":100," : "";
        return """
                {"model":"%s",%s"stream":%s,"system":"Be brief.","messages":[{"role":"user","content":"Hi"}]}"""
                .formatted(model, maxTokens, stream);
    }
}
