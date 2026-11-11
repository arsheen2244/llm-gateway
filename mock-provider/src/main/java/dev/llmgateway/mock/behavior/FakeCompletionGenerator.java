package dev.llmgateway.mock.behavior;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** Produces deterministic fake response text and rough token counts. */
@Component
public class FakeCompletionGenerator {

    private static final String[] WORDS =
            "This is a simulated response from the mock provider.".split(" ");

    /** Returns {@code count} text pieces. Joined together they form the full reply. */
    public List<String> chunks(int count) {
        List<String> pieces = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String word = WORDS[i % WORDS.length];
            pieces.add(i == 0 ? word : " " + word);
        }
        return pieces;
    }

    /**
     * Rough token estimate (about 4 characters per token, the usual rule of thumb
     * for English). Good enough for a mock; real providers report exact counts.
     */
    public int estimateTokens(String text) {
        return Math.max(1, (text.length() + 3) / 4);
    }
}
