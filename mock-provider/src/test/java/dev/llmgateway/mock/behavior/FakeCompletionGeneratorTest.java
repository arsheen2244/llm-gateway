package dev.llmgateway.mock.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FakeCompletionGeneratorTest {

    private final FakeCompletionGenerator generator = new FakeCompletionGenerator();

    @Test
    void chunksJoinIntoReadableText() {
        assertThat(String.join("", generator.chunks(3))).isEqualTo("This is a");
    }

    @Test
    void chunksWrapAroundWhenMoreAreRequestedThanWordsExist() {
        assertThat(generator.chunks(20)).hasSize(20);
    }

    @Test
    void tokenEstimateIsAboutFourCharactersPerToken() {
        assertThat(generator.estimateTokens("12345678")).isEqualTo(2);
        assertThat(generator.estimateTokens("")).isEqualTo(1);
    }
}
