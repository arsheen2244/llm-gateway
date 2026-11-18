package dev.llmgateway.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import org.junit.jupiter.api.Test;

/** What makes two requests "the same" for the cache, and what keeps them apart (note 32). */
class CacheKeysTest {

    private static final String CLIENT = "team-a";

    @Test
    void whitespaceDifferencesDoNotMatter() {
        assertThat(CacheKeys.exact(CLIENT, ask("What is  the capital\nof France?  ")))
                .isEqualTo(CacheKeys.exact(CLIENT, ask("What is the capital of France?")));
    }

    @Test
    void unicodeFormsOfTheSameCharacterAreEqual() {
        String composed = "café";    // é as one character
        String decomposed = "café"; // e + combining accent

        assertThat(CacheKeys.exact(CLIENT, ask(composed))).isEqualTo(CacheKeys.exact(CLIENT, ask(decomposed)));
    }

    @Test
    void caseIsKeptBecauseItCanChangeTheMeaning() {
        assertThat(CacheKeys.exact(CLIENT, ask("Who leads the US?")))
                .isNotEqualTo(CacheKeys.exact(CLIENT, ask("Who leads the us?")));
    }

    @Test
    void differentClientsNeverShareAKeyOrScope() {
        ChatCompletionRequest request = ask("Hello");

        assertThat(CacheKeys.exact("team-a", request)).isNotEqualTo(CacheKeys.exact("team-b", request));
        assertThat(CacheKeys.scope("team-a", request)).isNotEqualTo(CacheKeys.scope("team-b", request));
    }

    @Test
    void modelSystemPromptAndSamplingSettingsAreAllPartOfTheScope() {
        ChatCompletionRequest base = request("m", List.of(new ChatMessage("system", "Be brief."),
                new ChatMessage("user", "Hi")), 0.0, null, null, null);
        String scope = CacheKeys.scope(CLIENT, base);

        assertThat(CacheKeys.scope(CLIENT, request("other-model", base.messages(), 0.0, null, null, null))).isNotEqualTo(scope);
        assertThat(CacheKeys.scope(CLIENT, request("m", List.of(new ChatMessage("system", "Be detailed."),
                new ChatMessage("user", "Hi")), 0.0, null, null, null))).isNotEqualTo(scope);
        assertThat(CacheKeys.scope(CLIENT, request("m", base.messages(), 0.2, null, null, null))).isNotEqualTo(scope);
        assertThat(CacheKeys.scope(CLIENT, request("m", base.messages(), 0.0, 0.5, null, null))).isNotEqualTo(scope);
        assertThat(CacheKeys.scope(CLIENT, request("m", base.messages(), 0.0, null, 100, null))).isNotEqualTo(scope);
        assertThat(CacheKeys.scope(CLIENT, request("m", base.messages(), 0.0, null, null, List.of("END")))).isNotEqualTo(scope);
    }

    @Test
    void streamFlagIsNotPartOfTheKeySoAnswersCanBeReplayedEitherWay() {
        ChatCompletionRequest plain = ask("Hello");

        assertThat(CacheKeys.exact(CLIENT, plain.withStream(true, null))).isEqualTo(CacheKeys.exact(CLIENT, plain));
    }

    @Test
    void messageBoundariesMatter() {
        ChatCompletionRequest twoTurns = request("m", List.of(new ChatMessage("user", "ab"),
                new ChatMessage("assistant", "c")), 0.0, null, null, null);
        ChatCompletionRequest shifted = request("m", List.of(new ChatMessage("user", "a"),
                new ChatMessage("assistant", "bc")), 0.0, null, null, null);

        assertThat(CacheKeys.exact(CLIENT, twoTurns)).isNotEqualTo(CacheKeys.exact(CLIENT, shifted));
    }

    @Test
    void semanticTextIsTheNormalizedUserMessage() {
        ChatCompletionRequest request = request("m", List.of(new ChatMessage("system", "Be brief."),
                new ChatMessage("user", "  What is\n\nRedis? ")), 0.0, null, null, null);

        assertThat(CacheKeys.semanticText(request)).isEqualTo("What is Redis?");
    }

    private static ChatCompletionRequest ask(String userText) {
        return request("m", List.of(new ChatMessage("user", userText)), 0.0, null, null, null);
    }

    private static ChatCompletionRequest request(String model, List<ChatMessage> messages, Double temperature,
                                                 Double topP, Integer maxTokens, List<String> stop) {
        return new ChatCompletionRequest(model, messages, null, null, temperature, maxTokens, topP, stop);
    }
}
