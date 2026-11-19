package dev.llmgateway.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import dev.llmgateway.gateway.support.FakeClock;
import org.junit.jupiter.api.Test;

/** The in-memory store's semantic layer: a linear scan of the scope's prompts. */
class InMemoryCacheStoreTest {

    private final FakeClock clock = new FakeClock();
    private final InMemoryCacheStore store = new InMemoryCacheStore(clock);

    @Test
    void returnsTheMostSimilarEntryAboveTheThreshold() {
        store.putSemantic("s", vector(0.92), answer("good"), Duration.ofMinutes(1));
        store.putSemantic("s", vector(0.99), answer("best"), Duration.ofMinutes(1));
        store.putSemantic("s", vector(0.50), answer("far"), Duration.ofMinutes(1));

        assertThat(store.findSimilar("s", vector(1.0), 0.9))
                .hasValueSatisfying(match -> assertThat(match.answer().content()).isEqualTo("best"));
    }

    @Test
    void nothingAboveTheThresholdIsAMiss() {
        store.putSemantic("s", vector(0.8), answer("a"), Duration.ofMinutes(1));

        assertThat(store.findSimilar("s", vector(1.0), 0.9)).isEmpty();
        assertThat(store.findSimilar("other-scope", vector(0.8), 0.0)).isEmpty();
    }

    @Test
    void semanticEntriesExpire() {
        store.putSemantic("s", vector(1.0), answer("a"), Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(1));

        assertThat(store.findSimilar("s", vector(1.0), 0.9)).isEmpty();
    }

    private static CachedAnswer answer(String content) {
        return new CachedAnswer(content, "stop", "m", 1, 1);
    }

    private static float[] vector(double similarity) {
        float[] vector = new float[FakeEmbedder.DIMENSIONS];
        vector[0] = (float) similarity;
        vector[1] = (float) Math.sqrt(1 - similarity * similarity);
        return vector;
    }
}
