package dev.llmgateway.gateway.usage.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** No usage database: the gateway starts, chat works, and only the usage endpoint says 503. */
@TestPropertySource(properties = "gateway.usage.report.url=jdbc:postgresql://127.0.0.1:1/usage")
class UsageDatabaseDownIntegrationTest extends GatewayIntegrationTest {

    @Test
    void usageIs503AndChatIsUnaffected() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> usage = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/usage")).header("Authorization", "Bearer " + TEST_KEY)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(usage.statusCode()).isEqualTo(503);
        assertThat(json.readTree(usage.body()).at("/error/code").asText()).isEqualTo("usage_unavailable");
        assertThat(usage.body()).doesNotContain("jdbc", "127.0.0.1"); // no internals in the answer
        assertThat(elapsedMs).isLessThan(8_000); // bounded by the pool's 2 s wait
        assertThat(post(chatBody("mock-gpt", false)).statusCode()).isEqualTo(200);
    }
}
