package dev.llmgateway.gateway.resilience;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import dev.llmgateway.gateway.observability.LogFields;
import dev.llmgateway.gateway.provider.call.CallControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Hedged requests for non-streaming calls (design note 30).
 *
 * <ol>
 *   <li>Start the call on a virtual thread and wait up to the hedge delay: the recent p95
 *       latency for this provider + model, never below the floor, or a default until enough
 *       samples exist (owner's choice).</li>
 *   <li>Answered in time? Done (no hedge).</li>
 *   <li>Still waiting, the provider's breaker is CLOSED, and the hedge budget allows it
 *       (at most 5% of recent requests)? Send the same request again, to the same provider.</li>
 *   <li>The first success wins; the other call is cancelled through its {@link CallControl}.
 *       If both fail, the primary's failure is reported.</li>
 * </ol>
 * The breaker sees one result per attempt (the executor reports it): the hedge is part of
 * the attempt, not a separate vote.
 */
@Component
public class Hedger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Hedger.class);

    private final ResilienceProperties.Hedging settings;
    private final ConcurrentMap<String, LatencyTracker> latencies = new ConcurrentHashMap<>();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    public Hedger(ResilienceProperties properties) {
        this.settings = properties.hedging();
    }

    /**
     * Runs {@code call} (given a fresh {@link CallControl} each time it is started), hedging it
     * if it is slow.
     *
     * @param latencyKey which latency history to use and update, e.g. "provider|model"
     */
    public <T> T call(String latencyKey, ProviderGuards.Guard guard, Function<CallControl, T> call) {
        guard.hedgeBudget().recordRequest();
        if (!settings.enabled()) {
            return timed(latencyKey, call, new CallControl());
        }
        Duration delay = hedgeDelay(latencyKey);
        CallControl primaryControl = new CallControl();
        CompletableFuture<T> primary = start(latencyKey, call, primaryControl);
        try {
            return primary.get(delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            // fall through: maybe hedge
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        } catch (InterruptedException e) {
            primaryControl.stop(CallControl.Stop.CANCELLED);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for provider", e);
        }

        if (guard.breaker().state() != CircuitBreaker.State.CLOSED || !guard.hedgeBudget().tryAcquireRetry()) {
            return await(primary, primaryControl); // not allowed to hedge: keep waiting
        }
        MDC.put(LogFields.HEDGED, "true");
        log.info("No response after {} ms; sending a hedged request", delay.toMillis());
        CallControl hedgeControl = new CallControl();
        CompletableFuture<T> hedge = start(latencyKey, call, hedgeControl);
        return firstSuccess(primary, primaryControl, hedge, hedgeControl);
    }

    /** The current hedge delay for {@code latencyKey}. */
    Duration hedgeDelay(String latencyKey) {
        LatencyTracker tracker = latencies.get(latencyKey);
        if (tracker == null || tracker.count() < settings.minSamples()) {
            return settings.defaultDelay();
        }
        Duration observed = tracker.percentile(settings.percentile());
        return observed.compareTo(settings.floor()) < 0 ? settings.floor() : observed;
    }

    private <T> T firstSuccess(CompletableFuture<T> primary, CallControl primaryControl,
                               CompletableFuture<T> hedge, CallControl hedgeControl) {
        CompletableFuture<Integer> winner = new CompletableFuture<>();
        AtomicInteger failures = new AtomicInteger();
        primary.whenComplete((result, error) -> settle(winner, failures, error, 0));
        hedge.whenComplete((result, error) -> settle(winner, failures, error, 1));

        int won = await(winner, primaryControl, hedgeControl);
        if (won == 0) {
            hedgeControl.stop(CallControl.Stop.CANCELLED);
            return primary.join();
        }
        if (won == 1) {
            primaryControl.stop(CallControl.Stop.CANCELLED);
            log.info("Hedged request won");
            return hedge.join();
        }
        return await(primary, primaryControl); // both failed: report the primary's failure
    }

    private static void settle(CompletableFuture<Integer> winner, AtomicInteger failures, Throwable error, int index) {
        if (error == null) {
            winner.complete(index);
        } else if (failures.incrementAndGet() == 2) {
            winner.complete(-1);
        }
    }

    /** Starts {@code call} on a virtual thread, with the caller's logging context. */
    private <T> CompletableFuture<T> start(String latencyKey, Function<CallControl, T> call, CallControl control) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            if (mdc != null) {
                MDC.setContextMap(mdc); // the request ID etc. follow the work (note 14)
            }
            try {
                return timed(latencyKey, call, control);
            } finally {
                MDC.clear();
            }
        }, threads);
    }

    /** Runs the call and records its latency if it succeeds. */
    private <T> T timed(String latencyKey, Function<CallControl, T> call, CallControl control) {
        long start = System.nanoTime();
        T result = call.apply(control);
        latencies.computeIfAbsent(latencyKey, key -> new LatencyTracker(settings.sampleWindow()))
                .record(Duration.ofNanos(System.nanoTime() - start));
        return result;
    }

    private static <T> T await(CompletableFuture<T> future, CallControl... controls) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        } catch (InterruptedException e) {
            for (CallControl control : controls) {
                control.stop(CallControl.Stop.CANCELLED);
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for provider", e);
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause);
    }

    @Override
    public void close() {
        threads.close();
    }
}
