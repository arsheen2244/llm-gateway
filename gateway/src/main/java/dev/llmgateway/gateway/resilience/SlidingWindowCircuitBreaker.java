package dev.llmgateway.gateway.resilience;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The circuit breaker state machine: failure rate over a sliding time window
 * (design notes 28-29). The behaviour is specified by the {@link CircuitBreaker} javadoc and
 * pinned by {@code CircuitBreakerTest}.
 *
 * <p><b>State it keeps:</b>
 * <ul>
 *   <li>CLOSED: a time-ordered log of recent outcomes and a running count of the failures in
 *       it, so the failure rate is one division, not a recount.</li>
 *   <li>OPEN: the moment it opened.</li>
 *   <li>HALF_OPEN: how many trial calls are running and how many have succeeded.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> every public method is {@code synchronized}, so a check and the
 * update that follows it (e.g. "fewer than 3 trials? then take one") can never interleave
 * with another thread's. Design note 2 avoids {@code synchronized} on virtual threads only
 * around blocking I/O, which pins the carrier thread; these methods do no I/O and hold the
 * lock for microseconds.
 */
public class SlidingWindowCircuitBreaker implements CircuitBreaker {

    /** One recorded call result in the CLOSED state's window. */
    private record Outcome(long at, boolean failed) {
    }

    private final CircuitBreakerConfig config;
    private final Clock clock;

    private State state = State.CLOSED;
    private final Deque<Outcome> window = new ArrayDeque<>(); // oldest first
    private int failures;                                      // failures currently in window
    private long openedAt;
    private int trialsInUse;
    private int trialSuccesses;

    public SlidingWindowCircuitBreaker(CircuitBreakerConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    @Override
    public synchronized boolean tryAcquirePermission() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                if (clock.millis() - openedAt >= config.openDuration().toMillis()) {
                    // Waited long enough: move to HALF_OPEN lazily, and this caller is trial #1.
                    state = State.HALF_OPEN;
                    trialsInUse = 1;
                    trialSuccesses = 0;
                    yield true;
                }
                yield false;
            }
            case HALF_OPEN -> {
                if (trialsInUse + trialSuccesses < config.halfOpenTrialCalls()) {
                    trialsInUse++;
                    yield true;
                }
                yield false;
            }
        };
    }

    @Override
    public synchronized void onSuccess() {
        switch (state) {
            case CLOSED -> record(false);
            case HALF_OPEN -> {
                trialsInUse--;
                trialSuccesses++;
                if (trialSuccesses >= config.halfOpenTrialCalls()) {
                    close();
                }
            }
            case OPEN -> { } // a late result from a call allowed before it opened
        }
    }

    @Override
    public synchronized void onFailure() {
        switch (state) {
            case CLOSED -> {
                record(true);
                if (window.size() >= config.minimumCalls()
                        && (double) failures / window.size() >= config.failureRateThreshold()) {
                    open();
                }
            }
            case HALF_OPEN -> open(); // one failed trial is enough: back off for a full period
            case OPEN -> { }          // a late result; must not extend the open period
        }
    }

    @Override
    public synchronized void onIgnored() {
        if (state == State.HALF_OPEN && trialsInUse > 0) {
            trialsInUse--; // give the trial slot back for a call that can tell us something
        }
    }

    @Override
    public synchronized State state() {
        return state;
    }

    /** Adds an outcome to the window, then forgets everything older than the window. */
    private void record(boolean failed) {
        long now = clock.millis();
        window.addLast(new Outcome(now, failed));
        if (failed) {
            failures++;
        }
        long cutoff = now - config.window().toMillis();
        while (!window.isEmpty() && window.peekFirst().at() <= cutoff) {
            if (window.removeFirst().failed()) {
                failures--;
            }
        }
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.millis();
        resetWindow();
        trialsInUse = 0;
        trialSuccesses = 0;
    }

    /** A fresh start: failures from before the provider recovered no longer count. */
    private void close() {
        state = State.CLOSED;
        resetWindow();
    }

    private void resetWindow() {
        window.clear();
        failures = 0;
    }
}
