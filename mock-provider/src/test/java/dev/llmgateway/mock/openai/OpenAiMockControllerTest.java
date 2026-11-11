package dev.llmgateway.mock.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** Calls the running mock over real HTTP, the way the gateway will. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mock.chunk-count=3", "mock.chunk-delay-ms=0", "mock.latency-ms=0"})
class OpenAiMockControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    void normalRequestReturnsCompletionWithUsage() throws Exception {
        HttpResponse<String> response = post(body("mock-gpt", false), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode completion = json.readTree(response.body());
        assertThat(completion.at("/object").asText()).isEqualTo("chat.completion");
        assertThat(completion.at("/choices/0/message/content").asText()).isEqualTo("This is a");
        assertThat(completion.at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(completion.at("/usage/completion_tokens").asInt()).isEqualTo(3);
    }

    @Test
    void streamingRequestSendsRoleThenContentThenStopThenDone() throws Exception {
        HttpResponse<Stream<String>> response = post(body("mock-gpt", true), HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));
        List<String> data = dataLines(response.body());

        assertThat(data).hasSize(6); // role + 3 content + stop + [DONE]
        assertThat(json.readTree(data.get(0)).at("/choices/0/delta/role").asText()).isEqualTo("assistant");
        assertThat(json.readTree(data.get(1)).at("/choices/0/delta/content").asText()).isEqualTo("This");
        assertThat(json.readTree(data.get(4)).at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(data.get(5)).isEqualTo("[DONE]");
    }

    @Test
    void streamingWithIncludeUsageAddsUsageChunkBeforeDone() throws Exception {
        String body = """
                {"model":"mock-gpt","stream":true,"stream_options":{"include_usage":true},
                 "messages":[{"role":"user","content":"hi"}]}""";
        List<String> data = dataLines(post(body, HttpResponse.BodyHandlers.ofLines()).body());

        JsonNode usageChunk = json.readTree(data.get(data.size() - 2));
        assertThat(usageChunk.at("/choices").size()).isZero();
        assertThat(usageChunk.at("/usage/total_tokens").asInt()).isPositive();
    }

    @Test
    void forcedErrorReturnsOpenAiStyleErrorBody() throws Exception {
        HttpResponse<String> response = post(body("mock-error-503", false), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).at("/error/type").asText()).isEqualTo("server_error");
    }

    @Test
    void forcedMidStreamFailureDropsConnectionAfterSomeChunks() throws Exception {
        HttpResponse<Stream<String>> response =
                post(body("mock-fail-after-2", true), HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200); // headers were already sent

        List<String> received = new ArrayList<>();
        assertThatThrownBy(() -> response.body().forEach(received::add))
                .isInstanceOf(UncheckedIOException.class);

        List<String> data = received.stream().filter(line -> line.startsWith("data: ")).toList();
        assertThat(data).hasSize(3); // role + 2 content chunks, then the connection dies
        assertThat(data).noneMatch(line -> line.contains("[DONE]"));
    }

    private <T> HttpResponse<T> post(String body, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, handler);
    }

    private static String body(String model, boolean stream) {
        return """
                {"model":"%s","stream":%s,"messages":[{"role":"user","content":"Say hello"}]}"""
                .formatted(model, stream);
    }

    private static List<String> dataLines(Stream<String> lines) {
        return lines.filter(line -> line.startsWith("data: "))
                .map(line -> line.substring("data: ".length()))
                .toList();
    }
}
