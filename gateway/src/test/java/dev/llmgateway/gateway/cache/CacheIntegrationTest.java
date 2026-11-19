package dev.llmgateway.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import dev.llmgateway.gateway.support.MockProviderServer;
import org.junit.jupiter.api.Test;

/**
 * The cache through the real gateway and mock (in-memory store). Each test uses a unique prompt,
 * so tests never see each other's cached answers.
 */
class CacheIntegrationTest extends GatewayIntegrationTest {

    @Test
    void secondIdenticalRequestIsServedFromTheCacheWithoutCallingTheProvider() throws Exception {
        String body = cacheable(false);
        long before = providerCalls();

        HttpResponse<String> first = post(body);
        HttpResponse<String> second = post(body);

        assertThat(first.headers().firstValue("X-Cache")).hasValue("miss");
        assertThat(second.headers().firstValue("X-Cache")).hasValue("exact");
        assertThat(json.readTree(second.body()).at("/choices/0/message/content").asText())
                .isEqualTo(json.readTree(first.body()).at("/choices/0/message/content").asText());
        assertThat(providerCalls() - before).isEqualTo(1);
    }

    @Test
    void streamingClientGetsTheCachedAnswerReplayedAsSse() throws Exception {
        String prompt = uniquePrompt();
        post(cacheable(false, prompt)); // cached from a normal request

        HttpResponse<String> streamed = post(cacheable(true, prompt));

        assertThat(streamed.headers().firstValue("X-Cache")).hasValue("exact");
        assertThat(streamed.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(streamed.body()).contains("chat.completion.chunk").endsWith("data: [DONE]\n\n");
    }

    @Test
    void cacheControlNoCacheBypassesTheCache() throws Exception {
        String body = cacheable(false);
        post(body);
        long before = providerCalls();

        HttpResponse<String> bypassed = http.send(chatRequest().header("Cache-Control", "no-cache")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(bypassed.headers().firstValue("X-Cache")).hasValue("bypass");
        assertThat(providerCalls() - before).isEqualTo(1);
    }

    @Test
    void requestsWithoutALowTemperatureBypassTheCache() throws Exception {
        HttpResponse<String> response = post(chatBody("mock-gpt", false)); // no temperature set

        assertThat(response.headers().firstValue("X-Cache")).hasValue("bypass");
    }

    // ---- helpers ----

    private static String uniquePrompt() {
        return "Explain caching " + UUID.randomUUID();
    }

    private static String cacheable(boolean stream) {
        return cacheable(stream, uniquePrompt());
    }

    private static String cacheable(boolean stream, String prompt) {
        return """
                {"model":"mock-gpt","temperature":0,"stream":%s,"messages":[{"role":"user","content":"%s"}]}"""
                .formatted(stream, prompt);
    }

    private long providerCalls() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(MockProviderServer.baseUrl() + "/stats")).GET().build();
        return json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).path("requests").asLong();
    }
}
