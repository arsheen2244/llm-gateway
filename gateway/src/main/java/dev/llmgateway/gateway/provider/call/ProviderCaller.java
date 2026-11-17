package dev.llmgateway.gateway.provider.call;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.config.GatewayProperties;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderAdapter;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import dev.llmgateway.gateway.routing.ProviderConfig;
import org.springframework.stereotype.Component;

/**
 * Runs one provider call under deadlines (design note 26):
 * <ul>
 *   <li><b>connect</b> and <b>idle</b> (silence between bytes) are enforced by the HTTP
 *       client itself (note 16);</li>
 *   <li><b>first byte</b>: no response headers within the limit, so the call is stopped;</li>
 *   <li><b>total</b>: a non-streaming call must finish within the limit;</li>
 *   <li><b>max stream duration</b>: streams use this instead of the total, because a long,
 *       healthy answer must not be cut off by a deadline meant for normal calls.</li>
 * </ul>
 * A deadline fires as a timer that aborts the connection through {@link CallControl}. The
 * resulting failure is reported as {@code TIMEOUT}, saying which deadline it was.
 */
@Component
public class ProviderCaller implements AutoCloseable {

    private final GatewayProperties.Timeouts timeouts;
    /**
     * One platform thread is plenty: timer tasks only flip a flag and abort a socket.
     * Daemon, so it never keeps the JVM alive.
     */
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "provider-call-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    public ProviderCaller(GatewayProperties properties) {
        this.timeouts = properties.timeouts();
    }

    /** {@code adapter} with deadlines on every call. */
    public ProviderAdapter withDeadlines(ProviderAdapter adapter) {
        return new ProviderAdapter() {
            @Override
            public String type() {
                return adapter.type();
            }

            @Override
            public ChatCompletionResponse complete(ProviderConfig provider, ChatCompletionRequest request) {
                return ProviderCaller.this.complete(adapter, provider, request, new CallControl());
            }

            @Override
            public void stream(ProviderConfig provider, ChatCompletionRequest request, ChunkSink sink) {
                ProviderCaller.this.stream(adapter, provider, request, sink, new CallControl());
            }
        };
    }

    /** A non-streaming call under first-byte and total deadlines. {@code control} can stop it early. */
    public ChatCompletionResponse complete(ProviderAdapter adapter, ProviderConfig provider,
                                           ChatCompletionRequest request, CallControl control) {
        List<ScheduledFuture<?>> deadlines = List.of(
                schedule(control::firstByteDeadlinePassed, timeouts.firstByte()),
                schedule(() -> control.stop(CallControl.Stop.TOTAL_TIMEOUT), timeouts.total()));
        try {
            return control.runWith(() -> adapter.complete(provider, request));
        } catch (ProviderException e) {
            throw explain(provider, control, e);
        } finally {
            deadlines.forEach(deadline -> deadline.cancel(false));
        }
    }

    /** A streaming call under first-byte and max-duration deadlines. */
    public void stream(ProviderAdapter adapter, ProviderConfig provider, ChatCompletionRequest request,
                       ChunkSink sink, CallControl control) {
        List<ScheduledFuture<?>> deadlines = List.of(
                schedule(control::firstByteDeadlinePassed, timeouts.firstByte()),
                schedule(() -> control.stop(CallControl.Stop.TOTAL_TIMEOUT), timeouts.streamMaxDuration()));
        try {
            control.runWith(() -> {
                adapter.stream(provider, request, sink);
                return null;
            });
        } catch (ProviderException e) {
            throw explain(provider, control, e);
        } finally {
            deadlines.forEach(deadline -> deadline.cancel(false));
        }
    }

    /**
     * An aborted call surfaces as an I/O error. If one of our deadlines caused it, say so,
     * so it maps to 504 and counts as retryable. A stream already under way keeps
     * STREAM_INTERRUPTED (the client has received data), with the reason in the message.
     */
    private ProviderException explain(ProviderConfig provider, CallControl control, ProviderException e) {
        return switch (control.stopReason()) {
            case FIRST_BYTE_TIMEOUT -> ProviderException.of(provider.name(), Reason.TIMEOUT,
                    "No response from provider within " + timeouts.firstByte().toMillis() + " ms", e);
            case TOTAL_TIMEOUT -> e.reason() == Reason.STREAM_INTERRUPTED
                    ? ProviderException.of(provider.name(), Reason.STREAM_INTERRUPTED,
                    "Stream exceeded the maximum duration of " + timeouts.streamMaxDuration().toMillis() + " ms", e)
                    : ProviderException.of(provider.name(), Reason.TIMEOUT,
                    "Provider call exceeded " + timeouts.total().toMillis() + " ms", e);
            case CANCELLED, NONE -> e;
        };
    }

    private ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        return timers.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        timers.shutdownNow();
    }
}
