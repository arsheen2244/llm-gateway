package dev.llmgateway.gateway.provider.call;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.hc.core5.concurrent.Cancellable;

/**
 * A handle on one provider call, used to stop it from another thread: when a deadline
 * passes, or when a hedged call loses the race (design note 26).
 *
 * <p>How it reaches the HTTP request: the call runs inside {@link #runWith}, which puts this
 * handle in a thread-local. {@link CancellableRequestFactory} creates the Apache request on
 * that same thread and {@link #bind binds} it here. {@link #stop} then aborts the socket,
 * and the thread blocked reading from it fails immediately.
 */
public final class CallControl {

    /** Why the call was stopped. Only the first reason counts. */
    public enum Stop { NONE, FIRST_BYTE_TIMEOUT, TOTAL_TIMEOUT, CANCELLED }

    private static final ThreadLocal<CallControl> CURRENT = new ThreadLocal<>();

    private final AtomicReference<Stop> stop = new AtomicReference<>(Stop.NONE);
    private volatile Cancellable request;
    private volatile boolean firstByteReceived;

    /** Runs {@code call} with this handle as the current one, so its HTTP request binds to it. */
    public <T> T runWith(Supplier<T> call) {
        CallControl previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return call.get();
        } finally {
            CURRENT.set(previous);
        }
    }

    /** The handle of the call running on this thread, or null. */
    static CallControl current() {
        return CURRENT.get();
    }

    /** Called when the HTTP request object exists. If already stopped, abort it at once. */
    void bind(Cancellable httpRequest) {
        this.request = httpRequest;
        if (stop.get() != Stop.NONE) {
            httpRequest.cancel();
        }
    }

    /** The provider's response headers arrived: the first-byte deadline no longer applies. */
    void firstByteReceived() {
        firstByteReceived = true;
    }

    /** Fired by the first-byte timer: stops the call only if nothing has arrived yet. */
    void firstByteDeadlinePassed() {
        if (!firstByteReceived) {
            stop(Stop.FIRST_BYTE_TIMEOUT);
        }
    }

    /**
     * Stops the call (aborting its connection) if it isn't stopped already.
     *
     * @return true if this call to stop() was the one that stopped it
     */
    public boolean stop(Stop reason) {
        if (!stop.compareAndSet(Stop.NONE, reason)) {
            return false;
        }
        Cancellable bound = request;
        if (bound != null) {
            bound.cancel();
        }
        return true;
    }

    public Stop stopReason() {
        return stop.get();
    }
}
