package dev.llmgateway.gateway.resilience;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.observability.LogFields;
import dev.llmgateway.gateway.pipeline.ChatContext;
import dev.llmgateway.gateway.pipeline.ChatInterceptor;
import dev.llmgateway.gateway.provider.AdapterRegistry;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import dev.llmgateway.gateway.provider.call.CallControl;
import dev.llmgateway.gateway.provider.call.ProviderCaller;
import dev.llmgateway.gateway.resilience.CircuitBreaker.State;
import dev.llmgateway.gateway.routing.ProviderConfig;
import dev.llmgateway.gateway.routing.ProviderTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The end of the interceptor chain: calls providers with failover, retries and circuit
 * breakers (design note 27). Rate limiting runs before this, so however many attempts happen
 * here, the client's request is one reservation.
 *
 * <pre>
 * for each provider in the model's list:
 *     no API key                     -> skip
 *     for attempt 1..maxAttemptsPerProvider:
 *         attempt > 1: retry budget empty -> stop retrying here; else wait (backoff + jitter)
 *         breaker refuses            -> next provider
 *         call it (with deadlines)
 *           success                  -> return
 *           client error (400/404/413/422) -> throw: every provider would say the same
 *           stream already reached the client -> throw: no failover after the first byte
 *           retryable (429, 5xx, timeout, I/O) -> next attempt
 *           otherwise                -> next provider
 * all exhausted -> the last real failure, or CIRCUIT_OPEN if every breaker refused
 * </pre>
 */
@Component
public class ResilientExecutor implements ChatInterceptor.Chain {

    private static final Logger log = LoggerFactory.getLogger(ResilientExecutor.class);

    /** Waits between retries. An interface so tests don't have to really wait. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration);
    }

    private final AdapterRegistry adapters;
    private final ProviderCaller caller;
    private final ProviderGuards guards;
    private final ResilienceProperties properties;
    private final Backoff backoff;
    private final Sleeper sleeper;
    private final Hedger hedger;

    @Autowired
    public ResilientExecutor(AdapterRegistry adapters, ProviderCaller caller, ProviderGuards guards,
                             ResilienceProperties properties, Hedger hedger) {
        this(adapters, caller, guards, properties,
                new Backoff(properties.backoffBase(), properties.backoffCap(),
                        () -> ThreadLocalRandom.current().nextDouble()),
                ResilientExecutor::sleepFor, hedger);
    }

    public ResilientExecutor(AdapterRegistry adapters, ProviderCaller caller, ProviderGuards guards,
                             ResilienceProperties properties, Backoff backoff, Sleeper sleeper,
                             Hedger hedger) {
        this.adapters = adapters;
        this.caller = caller;
        this.guards = guards;
        this.properties = properties;
        this.backoff = backoff;
        this.sleeper = sleeper;
        this.hedger = hedger;
    }

    @Override
    public ChatCompletionResponse complete(ChatContext context) {
        // Non-streaming calls may be hedged (note 30); each started call gets its own CallControl.
        return execute(context, (provider, request, guard) -> hedger.call(provider.name() + "|" + request.model(),
                guard, control -> caller.complete(adapters.forType(provider.type()), provider, request, control)),
                () -> false);
    }

    /** Streams fail over only until the first chunk has been handed on towards the client. */
    @Override
    public void stream(ChatContext context, ChunkSink sink) {
        boolean[] delivered = {false};
        ChunkSink tracking = chunk -> {
            delivered[0] = true;
            sink.accept(chunk);
        };
        execute(context, (provider, request, guard) -> {
            caller.stream(adapters.forType(provider.type()), provider, request, tracking, new CallControl());
            return null;
        }, () -> delivered[0]);
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T call(ProviderConfig provider, ChatCompletionRequest request, ProviderGuards.Guard guard);
    }

    private <T> T execute(ChatContext context, Attempt<T> attempt, BooleanSupplier reachedClient) {
        ProviderException lastFailure = null; // a provider was called and failed
        ProviderException firstSkip = null;   // a provider was skipped (no key, breaker open)
        int attempts = 0;

        for (ProviderTarget target : context.targets()) {
            ProviderConfig provider = target.provider();
            if (!provider.hasApiKey()) {
                firstSkip = firstSkip != null ? firstSkip : ProviderException.of(provider.name(),
                        Reason.NOT_CONFIGURED, "No API key configured for provider '" + provider.name() + "'", null);
                continue;
            }
            ProviderGuards.Guard guard = guards.forProvider(provider.name());
            ChatCompletionRequest request = context.request().withModel(target.upstreamModel(context.request().model()));

            for (int n = 1; n <= properties.maxAttemptsPerProvider(); n++) {
                if (n == 1) {
                    guard.budget().recordRequest();
                } else {
                    if (!guard.budget().tryAcquireRetry()) {
                        log.info("Retry budget for provider={} exhausted; not retrying", provider.name());
                        break;
                    }
                    sleeper.sleep(backoff.beforeRetry(n - 1));
                }
                if (!acquire(guard, provider)) {
                    firstSkip = firstSkip != null ? firstSkip : ProviderException.of(provider.name(),
                            Reason.CIRCUIT_OPEN, "Provider '" + provider.name() + "' is unavailable (circuit open)", null);
                    break;
                }

                attempts++;
                context.trace().attempting(provider.name(), request.model());
                MDC.put(LogFields.PROVIDER, provider.name());
                MDC.put(LogFields.ATTEMPTS, String.valueOf(attempts));
                try {
                    T result = attempt.call(provider, request, guard);
                    report(guard, provider, Outcome.SUCCESS);
                    return result;
                } catch (ProviderException e) {
                    report(guard, provider, countsAgainstHealth(e) ? Outcome.FAILURE : Outcome.IGNORED);
                    lastFailure = e;
                    if (reachedClient.getAsBoolean() || isClientError(e)) {
                        throw e;
                    }
                    if (!e.isRetryable()) {
                        break; // e.g. our key rejected (401/403): another provider may work
                    }
                    log.info("Provider call failed (provider={} attempt={} reason={} status={}); will retry or fail over",
                            provider.name(), n, e.reason(), e.upstreamStatus());
                } catch (RuntimeException e) {
                    // Not about the provider's health: the client went away, or a bug. Release
                    // the breaker permission without counting it, and stop.
                    report(guard, provider, Outcome.IGNORED);
                    throw e;
                }
            }
        }
        throw lastFailure != null ? lastFailure : firstSkip;
    }

    private enum Outcome { SUCCESS, FAILURE, IGNORED }

    private boolean acquire(ProviderGuards.Guard guard, ProviderConfig provider) {
        State before = guard.breaker().state();
        boolean permitted = guard.breaker().tryAcquirePermission();
        logTransition(provider, before, guard.breaker().state());
        return permitted;
    }

    private void report(ProviderGuards.Guard guard, ProviderConfig provider, Outcome outcome) {
        State before = guard.breaker().state();
        switch (outcome) {
            case SUCCESS -> guard.breaker().onSuccess();
            case FAILURE -> guard.breaker().onFailure();
            case IGNORED -> guard.breaker().onIgnored();
        }
        logTransition(provider, before, guard.breaker().state());
    }

    private static void logTransition(ProviderConfig provider, State before, State after) {
        if (before != after) {
            log.warn("Circuit breaker for provider={}: {} -> {}", provider.name(), before, after);
        }
    }

    /** Failures that say the provider is unhealthy for us; these feed the breaker. */
    static boolean countsAgainstHealth(ProviderException e) {
        return switch (e.reason()) {
            case TIMEOUT, IO_ERROR, INVALID_RESPONSE, STREAM_INTERRUPTED -> true;
            case ERROR_RESPONSE -> e.upstreamStatus() == 429 || e.upstreamStatus() >= 500;
            case NOT_CONFIGURED, CIRCUIT_OPEN -> false;
        };
    }

    /** The client's own mistake: retrying or asking another provider would get the same answer. */
    static boolean isClientError(ProviderException e) {
        if (e.reason() != Reason.ERROR_RESPONSE) {
            return false;
        }
        int status = e.upstreamStatus();
        return status == 400 || status == 404 || status == 413 || status == 422;
    }

    private static void sleepFor(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", e);
        }
    }
}
