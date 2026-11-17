package dev.llmgateway.mock.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** /stats counts what the mock received, by outcome, over real HTTP. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mock.chunk-count=2", "mock.chunk-delay-ms=0", "mock.latency-ms=0"})
class StatsControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    void countsRequestsByOutcome() throws Exception {
        JsonNode before = stats();

        chat("mock-gpt");
        chat("mock-error-503");
        chat("mock-slow-50");

        JsonNode after = stats();
        assertThat(delta(before, after, "requests")).isEqualTo(3);
        assertThat(delta(before, after, "errors")).isEqualTo(1);
        assertThat(delta(before, after, "slow")).isEqualTo(1);
    }

    private JsonNode stats() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/stats")).GET().build();
        return json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    private void chat(String model) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer k")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .build();
        http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static long delta(JsonNode before, JsonNode after, String field) {
        return after.path(field).asLong() - before.path(field).asLong();
    }
}
