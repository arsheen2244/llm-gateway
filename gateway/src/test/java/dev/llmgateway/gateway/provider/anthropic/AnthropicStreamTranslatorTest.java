package dev.llmgateway.gateway.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import org.junit.jupiter.api.Test;

class AnthropicStreamTranslatorTest {

    private final AnthropicStreamTranslator translator =
            new AnthropicStreamTranslator("p", new ObjectMapper(), Clock.systemUTC());
    private final List<ChatCompletionChunk> chunks = new ArrayList<>();

    @Test
    void fullEventSequenceBecomesOpenAiChunks() {
        send("message_start", """
                {"type":"message_start","message":{"id":"msg_1","model":"claude-x","usage":{"input_tokens":12,"output_tokens":1}}}""");
        send("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        send("ping", "{\"type\":\"ping\"}");
        send("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}");
        send("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" there\"}}");
        send("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
        send("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}");
        assertThat(translator.finished()).isFalse();
        send("message_stop", "{\"type\":\"message_stop\"}");

        assertThat(translator.finished()).isTrue();
        assertThat(chunks).hasSize(5); // role, 2 content, finish, usage
        assertThat(chunks.get(0).choices().get(0).delta().role()).isEqualTo("assistant");
        assertThat(chunks.get(1).choices().get(0).delta().content()).isEqualTo("Hi");
        assertThat(chunks.get(2).choices().get(0).delta().content()).isEqualTo(" there");
        assertThat(chunks.get(3).choices().get(0).finishReason()).isEqualTo("stop");
        ChatCompletionChunk usage = chunks.get(4);
        assertThat(usage.usageOnly()).isTrue();
        assertThat(usage.usage().promptTokens()).isEqualTo(12);
        assertThat(usage.usage().completionTokens()).isEqualTo(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.id()).isEqualTo("msg_1");
            assertThat(chunk.model()).isEqualTo("claude-x");
            assertThat(chunk.object()).isEqualTo("chat.completion.chunk");
        });
    }

    @Test
    void errorEventBecomesStreamInterrupted() {
        send("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"model\":\"c\",\"usage\":{}}}");

        assertThatThrownBy(() -> send("error", "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.STREAM_INTERRUPTED);
                    assertThat(e.getMessage()).isEqualTo("Overloaded");
                });
    }

    @Test
    void unknownEventTypesAreIgnored() {
        send("some_future_event", "{\"type\":\"some_future_event\"}");

        assertThat(chunks).isEmpty();
    }

    private void send(String event, String data) {
        translator.onEvent(event, data, chunks::add);
    }
}
