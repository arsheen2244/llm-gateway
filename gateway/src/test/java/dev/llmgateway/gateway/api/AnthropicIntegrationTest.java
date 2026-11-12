package dev.llmgateway.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmgateway.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * OpenAI-format requests to a "mock-claude-*" model go through the Anthropic
 * adapter to the mock's Anthropic endpoint, and come back in OpenAI format.
 */
class AnthropicIntegrationTest extends GatewayIntegrationTest {

    @Test
    void normalRequestIsTranslatedBothWays() throws Exception {
        String body = """
                {"model":"mock-claude-3","messages":[
                  {"role":"system","content":"Be brief."},
                  {"role":"user","content":"Hi"}]}""";

        HttpResponse<String> response = post(body);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode completion = json.readTree(response.body());
        assertThat(completion.at("/object").asText()).isEqualTo("chat.completion");
        assertThat(completion.at("/choices/0/message/content").asText()).isEqualTo("This is a");
        assertThat(completion.at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(completion.at("/usage/completion_tokens").asInt()).isEqualTo(3);
    }

    @Test
    void streamIsTranslatedIntoOpenAiChunks() throws Exception {
        List<String> data = dataLines(post(chatBody("mock-claude-3", true), HttpResponse.BodyHandlers.ofLines()).body());

        // role, 3 content, finish, [DONE] (usage chunk held back: not requested)
        assertThat(data).hasSize(6);
        assertThat(json.readTree(data.get(0)).at("/choices/0/delta/role").asText()).isEqualTo("assistant");
        assertThat(json.readTree(data.get(1)).at("/object").asText()).isEqualTo("chat.completion.chunk");
        assertThat(json.readTree(data.get(4)).at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(data.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void overloadedProviderBecomes503() throws Exception {
        HttpResponse<String> response = post(chatBody("mock-claude-error-529", false));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).at("/error/code").asText()).isEqualTo("provider_unavailable");
    }

    @Test
    void midStreamFailureEndsWithCleanErrorEvent() throws Exception {
        List<String> data = dataLines(
                post(chatBody("mock-claude-fail-after-1", true), HttpResponse.BodyHandlers.ofLines()).body());

        assertThat(json.readTree(data.getLast()).at("/error/code").asText()).isEqualTo("stream_interrupted");
        assertThat(data).doesNotContain("[DONE]");
    }

    @Test
    void realAnthropicWithoutKeyIs503() throws Exception {
        assertThat(post(chatBody("claude-sonnet-4", false)).statusCode()).isEqualTo(503);
    }

    private static List<String> dataLines(java.util.stream.Stream<String> lines) {
        return lines.filter(line -> line.startsWith("data: ")).map(line -> line.substring(6)).toList();
    }
}
