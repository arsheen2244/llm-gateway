package dev.llmgateway.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import org.junit.jupiter.api.Test;

/** Which requests may use the cache (owner's choice; note 32). */
class CacheEligibilityTest {

    private final CacheEligibility eligibility = new CacheEligibility(0.3);

    @Test
    void lowExplicitTemperatureIsEligible() {
        assertThat(eligibility.exactEligible(request(0.0, user("Hi")))).isTrue();
        assertThat(eligibility.exactEligible(request(0.3, user("Hi")))).isTrue();
    }

    @Test
    void higherOrUnsetTemperatureIsNot() {
        assertThat(eligibility.exactEligible(request(0.31, user("Hi")))).isFalse();
        // Unset means the provider's default (1.0 at OpenAI): the caller didn't ask for stability.
        assertThat(eligibility.exactEligible(request(null, user("Hi")))).isFalse();
    }

    @Test
    void singleTurnPromptsAreSemanticallyEligible() {
        assertThat(eligibility.semanticEligible(request(0.0, user("Hi")))).isTrue();
        assertThat(eligibility.semanticEligible(request(0.0, system("Be brief."), user("Hi")))).isTrue();
    }

    @Test
    void conversationsAreOnlyExactlyEligible() {
        ChatCompletionRequest conversation = request(0.0, user("Hi"), assistant("Hello!"), user("And then?"));

        assertThat(eligibility.exactEligible(conversation)).isTrue();
        assertThat(eligibility.semanticEligible(conversation)).isFalse();
    }

    @Test
    void oddShapesAreNotSemanticallyEligible() {
        assertThat(eligibility.semanticEligible(request(0.0, user("a"), user("b")))).isFalse();
        assertThat(eligibility.semanticEligible(request(0.0, user("a"), system("late system")))).isFalse();
        assertThat(eligibility.semanticEligible(request(0.0, system("only system")))).isFalse();
    }

    @Test
    void semanticEligibilityAlsoNeedsALowTemperature() {
        assertThat(eligibility.semanticEligible(request(0.9, user("Hi")))).isFalse();
    }

    private static ChatCompletionRequest request(Double temperature, ChatMessage... messages) {
        return new ChatCompletionRequest("m", List.of(messages), null, null, temperature, null, null, null);
    }

    private static ChatMessage user(String text) {
        return new ChatMessage("user", text);
    }

    private static ChatMessage system(String text) {
        return new ChatMessage("system", text);
    }

    private static ChatMessage assistant(String text) {
        return new ChatMessage("assistant", text);
    }
}
