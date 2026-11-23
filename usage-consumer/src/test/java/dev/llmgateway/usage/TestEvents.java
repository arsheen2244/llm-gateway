package dev.llmgateway.usage;

import java.time.Instant;
import java.util.UUID;

import dev.llmgateway.usage.UsageEvent.Status;

/** Usage events for tests. */
final class TestEvents {

    static final Instant NOON = Instant.parse("2026-11-20T12:00:00Z");

    private TestEvents() {
    }

    /** A successful, uncached call to mock-openai's mock-gpt. */
    static UsageEvent success(String client, int promptTokens, int completionTokens) {
        return event(client, "mock-openai", "mock-gpt", promptTokens, completionTokens, "miss", Status.SUCCESS, NOON);
    }

    static UsageEvent event(String client, String provider, String upstreamModel, int promptTokens,
                            int completionTokens, String cache, Status status, Instant at) {
        return new UsageEvent(UUID.randomUUID(), client, "mock-gpt", provider, upstreamModel, promptTokens,
                completionTokens, 10L, cache, status, false, at);
    }

    /** A client id no other test uses, so tests sharing a database don't see each other. */
    static String newClient() {
        return "client-" + UUID.randomUUID();
    }
}
