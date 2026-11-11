package dev.llmgateway.mock.behavior;

/** Sleep helper. Sleeping is cheap here because requests run on virtual threads. */
public final class Pause {

    private Pause() {
    }

    public static void millis(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // keep the interrupt visible to callers
            throw new IllegalStateException("Interrupted while simulating latency", e);
        }
    }
}
