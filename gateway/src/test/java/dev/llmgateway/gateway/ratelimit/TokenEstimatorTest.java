package dev.llmgateway.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator(1000);

    @Test
    void promptIsCharactersDividedByFourRoundedUpAcrossAllMessages() {
        // 8 + 5 = 13 characters -> 4 tokens (rounded up)
        ChatCompletionRequest request = request(List.of(
                new ChatMessage("system", "Be brief"), new ChatMessage("user", "Hello")), null);

        assertThat(estimator.promptTokens(request)).isEqualTo(4);
    }

    @Test
    void completionUsesMaxTokensWhenTheClientSetsIt() {
        assertThat(estimator.estimate(request(List.of(new ChatMessage("user", "abcd")), 50))).isEqualTo(1 + 50);
    }

    @Test
    void completionFallsBackToTheConfiguredDefault() {
        assertThat(estimator.estimate(request(List.of(new ChatMessage("user", "abcd")), null))).isEqualTo(1 + 1000);
    }

    @Test
    void emptyContentCostsNothingForThePrompt() {
        assertThat(estimator.promptTokens(request(List.of(new ChatMessage("user", "")), null))).isZero();
    }

    static ChatCompletionRequest request(List<ChatMessage> messages, Integer maxTokens) {
        return new ChatCompletionRequest("m", messages, null, null, null, maxTokens, null, null);
    }
}
