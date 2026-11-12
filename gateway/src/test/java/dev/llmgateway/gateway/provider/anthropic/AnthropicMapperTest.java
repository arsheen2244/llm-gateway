package dev.llmgateway.gateway.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import dev.llmgateway.gateway.provider.anthropic.AnthropicMapper.MessagesRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnthropicMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC);

    @Test
    void systemMessagesMoveToTopLevelFieldInOrder() {
        MessagesRequest mapped = AnthropicMapper.toRequest(request(List.of(
                new ChatMessage("system", "Be brief."),
                new ChatMessage("user", "Hi"),
                new ChatMessage("system", "Use English."),
                new ChatMessage("assistant", "Hello"))), false);

        assertThat(mapped.system()).isEqualTo("Be brief.\n\nUse English.");
        assertThat(mapped.messages()).extracting(AnthropicMapper.Message::role).containsExactly("user", "assistant");
    }

    @Test
    void maxTokensDefaultsWhenClientOmitsIt() {
        MessagesRequest mapped = AnthropicMapper.toRequest(request(List.of(new ChatMessage("user", "Hi"))), false);

        assertThat(mapped.maxTokens()).isEqualTo(AnthropicMapper.DEFAULT_MAX_TOKENS);
        assertThat(mapped.system()).isNull();
    }

    @Test
    void stopBecomesStopSequencesAndSettingsPassThrough() throws Exception {
        ChatCompletionRequest request = new ChatCompletionRequest("claude-x",
                List.of(new ChatMessage("user", "Hi")), true, null, 1.5, 100, 0.9, List.of("END"));

        JsonNode body = json.valueToTree(AnthropicMapper.toRequest(request, true));

        assertThat(body.path("stop_sequences").get(0).asText()).isEqualTo("END");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(100);
        assertThat(body.path("temperature").asDouble()).isEqualTo(1.5); // not rescaled
        assertThat(body.path("top_p").asDouble()).isEqualTo(0.9);
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.has("system")).isFalse(); // nulls are left out
    }

    @Test
    void responseBecomesChatCompletion() throws Exception {
        JsonNode message = json.readTree("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-x",
                 "content":[{"type":"text","text":"Hello "},{"type":"text","text":"there"}],
                 "stop_reason":"max_tokens",
                 "usage":{"input_tokens":10,"output_tokens":5}}""");

        ChatCompletionResponse response = AnthropicMapper.toResponse(message, clock);

        assertThat(response.id()).isEqualTo("msg_1");
        assertThat(response.object()).isEqualTo("chat.completion");
        assertThat(response.created()).isEqualTo(1_700_000_000L);
        assertThat(response.choices().get(0).message().content()).isEqualTo("Hello there");
        assertThat(response.choices().get(0).finishReason()).isEqualTo("length");
        assertThat(response.usage().totalTokens()).isEqualTo(15);
    }

    @Test
    void promptCacheTokensCountAsPromptTokens() throws Exception {
        JsonNode usage = json.readTree("""
                {"input_tokens":10,"cache_creation_input_tokens":100,"cache_read_input_tokens":1000,"output_tokens":5}""");

        assertThat(AnthropicMapper.usage(usage, usage).promptTokens()).isEqualTo(1110);
    }

    @ParameterizedTest
    @CsvSource({"end_turn, stop", "stop_sequence, stop", "max_tokens, length",
            "tool_use, tool_calls", "refusal, content_filter", "something_new, stop"})
    void mapsStopReasons(String anthropic, String openAi) {
        assertThat(AnthropicMapper.finishReason(anthropic)).isEqualTo(openAi);
    }

    private static ChatCompletionRequest request(List<ChatMessage> messages) {
        return new ChatCompletionRequest("claude-x", messages, null, null, null, null, null, null);
    }
}
