package dev.llmgateway.gateway.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** A clock that only moves when a test tells it to. */
public final class FakeClock extends Clock {

    private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);

    public void advance(Duration duration) {
        millis.addAndGet(duration.toMillis());
    }

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
