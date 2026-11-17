package dev.llmgateway.gateway.ratelimit;

import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.api.dto.Usage;
import dev.llmgateway.gateway.auth.ClientIdentity;
import dev.llmgateway.gateway.pipeline.ChatContext;
import dev.llmgateway.gateway.pipeline.ChatInterceptor;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies each client's request and token limits around the provider call
 * (design note 19).
 *
 * <ol>
 *   <li><b>Before:</b> estimate the tokens and ask the limiter. If refused, throw
 *       {@link RateLimitExceededException} (429) and never call the provider. If allowed,
 *       publish the {@code x-ratelimit-*} headers.</li>
 *   <li><b>After:</b> reconcile the reservation with what really happened:
 *       <ul>
 *         <li>usage reported: replace the estimate with the actual total;</li>
 *         <li>the provider failed before producing anything: refund all reserved tokens
 *             (the request itself still counts);</li>
 *         <li>otherwise (stream broke, client left, no usage): keep the estimate, since
 *             we cannot know what the provider billed.</li>
 *       </ul></li>
 * </ol>
 * A failed reconcile is logged and never fails the client's request.
 */
public class RateLimitInterceptor implements ChatInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimiter limiter;
    private final TokenEstimator estimator;
    private final RateLimitProperties properties;

    public RateLimitInterceptor(RateLimiter limiter, TokenEstimator estimator, RateLimitProperties properties) {
        this.limiter = limiter;
        this.estimator = estimator;
        this.properties = properties;
    }

    @Override
    public ChatCompletionResponse complete(ChatContext context, Chain chain) {
        Reservation reservation = admit(context);
        try {
            ChatCompletionResponse response = chain.complete(context);
            Usage usage = response.usage();
            if (usage != null) {
                settle(reservation, usage.totalTokens());
            }
            return response;
        } catch (ProviderException e) {
            refundIfNothingWasProduced(reservation, e);
            throw e;
        }
    }

    @Override
    public void stream(ChatContext context, ChunkSink sink, Chain chain) {
        Reservation reservation = admit(context);
        Usage[] usage = {null}; // set from the final usage chunk, if one arrives
        try {
            chain.stream(context, chunk -> {
                if (chunk.usage() != null) {
                    usage[0] = chunk.usage();
                }
                sink.accept(chunk);
            });
            if (usage[0] != null) {
                settle(reservation, usage[0].totalTokens());
            }
        } catch (ProviderException e) {
            refundIfNothingWasProduced(reservation, e);
            throw e;
        }
    }

    private Reservation admit(ChatContext context) {
        ClientIdentity client = context.client();
        Limits limits = new Limits(client.requestsPerMinute(), client.tokensPerMinute(), properties.window());
        long estimate = estimator.estimate(context.request());
        RateLimitDecision decision = limiter.tryAcquire(client.id(), limits, estimate);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(message(limits, decision, estimate), decision,
                    RateLimitHeaders.of(limits, decision));
        }
        context.responseHeaders().setAll(RateLimitHeaders.of(limits, decision));
        return decision.reservation();
    }

    /**
     * Failures with no output at all (an HTTP error, timeout or connection failure
     * before anything arrived) are not billed by providers, so the tokens go back.
     */
    private void refundIfNothingWasProduced(Reservation reservation, ProviderException e) {
        switch (e.reason()) {
            case ERROR_RESPONSE, TIMEOUT, IO_ERROR, NOT_CONFIGURED, CIRCUIT_OPEN -> settle(reservation, 0);
            case STREAM_INTERRUPTED, INVALID_RESPONSE -> { } // something may have been billed
        }
    }

    private void settle(Reservation reservation, long actualTokens) {
        try {
            limiter.reconcile(reservation, actualTokens);
        } catch (RuntimeException e) {
            // Accounting must never break a request that already succeeded or failed on its own.
            log.warn("Rate-limit reconcile failed for client={}: {}", reservation.clientId(), e.toString());
        }
    }

    private static String message(Limits limits, RateLimitDecision decision, long estimate) {
        if (decision.outcome() == RateLimitDecision.Outcome.TOO_LARGE) {
            return "Request too large for your token limit: estimated " + estimate + " tokens, limit "
                    + limits.maxTokens() + " per minute. Lower max_tokens or shorten the prompt.";
        }
        String which = decision.remainingRequests() < 1
                ? "requests per minute (limit " + limits.maxRequests() + ")"
                : "tokens per minute (limit " + limits.maxTokens() + ", this request needs about " + estimate + ")";
        long seconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
        return "Rate limit reached for " + which + ". Retry after " + seconds + " s.";
    }
}
