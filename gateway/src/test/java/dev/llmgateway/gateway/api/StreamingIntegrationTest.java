package dev.llmgateway.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.Test;

/** Streaming requests through the real gateway to the real mock (3 content chunks). */
class StreamingIntegrationTest extends GatewayIntegrationTest {

    @Test
    void streamIsRelayedChunkByChunkAndEndsWithDone() throws Exception {
        HttpResponse<Stream<String>> response = post(chatBody("mock-gpt", true), HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));

        List<String> data = dataLines(response.body());
        // role chunk, 3 content chunks, finish chunk, [DONE]. The usage chunk
        // is held back because this client did not ask for it.
        assertThat(data).hasSize(6);
        assertThat(json.readTree(data.get(0)).at("/choices/0/delta/role").asText()).isEqualTo("assistant");
        assertThat(contentOf(data)).isEqualTo("This is a");
        assertThat(json.readTree(data.get(4)).at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(data.get(5)).isEqualTo("[DONE]");
    }

    @Test
    void usageChunkIsForwardedWhenClientAsksForIt() throws Exception {
        String body = """
                {"model":"mock-gpt","stream":true,"stream_options":{"include_usage":true},
                 "messages":[{"role":"user","content":"hi"}]}""";

        List<String> data = dataLines(post(body, HttpResponse.BodyHandlers.ofLines()).body());

        JsonNode usageChunk = json.readTree(data.get(data.size() - 2));
        assertThat(usageChunk.at("/usage/total_tokens").asInt()).isPositive();
        assertThat(data.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void midStreamFailureEndsWithCleanErrorEventAndNoDone() throws Exception {
        HttpResponse<Stream<String>> response =
                post(chatBody("mock-fail-after-2", true), HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);

        // Reading to the end must NOT throw: the gateway closes the stream cleanly,
        // even though the provider's connection died abruptly.
        List<String> data = dataLines(response.body());

        assertThat(contentOf(data)).isEqualTo("This is"); // the 2 chunks that made it
        JsonNode error = json.readTree(data.getLast()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("stream_interrupted");
        assertThat(error.path("type").asText()).isEqualTo("upstream_error");
        assertThat(data).doesNotContain("[DONE]");
    }

    @Test
    void failureBeforeFirstChunkIsAProperHttpErrorEvenForStreamingClients() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(chatBody("mock-error-503", true)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(json.readTree(response.body()).at("/error/code").asText()).isEqualTo("provider_unavailable");
    }

    @Test
    void unknownModelInStreamingModeIs404Json() throws Exception {
        HttpResponse<String> response = post(chatBody("nope", true));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private static List<String> dataLines(Stream<String> lines) {
        return lines.filter(line -> line.startsWith("data: "))
                .map(line -> line.substring("data: ".length()))
                .toList();
    }

    private String contentOf(List<String> data) throws Exception {
        StringBuilder text = new StringBuilder();
        for (String line : data) {
            if (!line.equals("[DONE]")) {
                text.append(json.readTree(line).at("/choices/0/delta/content").asText(""));
            }
        }
        return text.toString();
    }
}
