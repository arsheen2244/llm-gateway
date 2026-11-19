package dev.llmgateway.gateway.usage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps every published event so a test can inspect it. */
public final class RecordingUsageSink implements UsageSink {

    public final List<UsageEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(UsageEvent event) {
        events.add(event);
    }

    public UsageEvent only() {
        if (events.size() != 1) {
            throw new AssertionError("Expected exactly one usage event, got " + events);
        }
        return events.get(0);
    }
}
