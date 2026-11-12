package dev.llmgateway.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Checks the real log output: one JSON access line per request, carrying the request's context. */
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingIntegrationTest extends GatewayIntegrationTest {

    @Test
    void responseCarriesRequestIdHeader() throws Exception {
        HttpResponse<String> response = post(chatBody("mock-gpt", false));

        assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).isPresent();
    }

    @Test
    void accessLogLineIsJsonWithRequestIdModelProviderAndStatus(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header(RequestIdFilter.HEADER, "trace-me-42")
                .POST(HttpRequest.BodyPublishers.ofString(chatBody("mock-gpt", false)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).hasValue("trace-me-42");
        String accessLine = output.getOut().lines()
                .filter(line -> line.contains("trace-me-42") && line.contains("\"logger\":\"access\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no access log line for trace-me-42 in:\n" + output.getOut()));
        assertThat(accessLine)
                .startsWith("{")
                .contains("\"requestId\":\"trace-me-42\"")
                .contains("\"model\":\"mock-gpt\"")
                .contains("\"provider\":\"mock-openai\"")
                .contains("\"status\":200");
    }

    @Test
    void interruptedStreamIsVisibleInAccessLogDespiteStatus200(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header(RequestIdFilter.HEADER, "broken-stream-7")
                .POST(HttpRequest.BodyPublishers.ofString(chatBody("mock-fail-after-1", true)))
                .build();

        http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(output.getOut().lines()
                .filter(line -> line.contains("broken-stream-7") && line.contains("\"logger\":\"access\"")))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("\"streamOutcome\":\"interrupted\""));
    }
}
