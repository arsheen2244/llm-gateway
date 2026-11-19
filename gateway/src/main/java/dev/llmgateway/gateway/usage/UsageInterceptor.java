package dev.llmgateway.gateway.usage;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.api.dto.Usage;
import dev.llmgateway.gateway.cache.CacheHeaders;
import dev.llmgateway.gateway.pipeline.ChatContext;
import dev.llmgateway.gateway.pipeline.ChatInterceptor;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.ratelimit.RateLimitExceededException;
import dev.llmgateway.gateway.streaming.ClientDisconnectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

/**
 * Records one {@link UsageEvent} per request (design note 37).
 *
 * <p>First in the chain, ahead of rate limiting, so it sees every outcome: answers from a
 * provider or the cache, rate-limit refusals and failures. The event is published after the
 * request is done; publishing is wrapped so that it can never change what the client gets.
 */
public class UsageInterceptor implements ChatInterceptor, Ordered {

    /** Before rate limiting (100), so refused requests are recorded too. */
    public static final int ORDER = 50;

    private static final Logger log = LoggerFactory.getLogger(UsageInterceptor.class);

    private final UsageSink sink;
    private final Clock clock;

    public UsageInterceptor(UsageSink sink, Clock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatCompletionResponse complete(ChatContext context, Chain chain) {
        Instant start = clock.instant();
        try {
            ChatCompletionResponse response = chain.complete(context);
            emit(context, start, response.usage(), UsageStatus.SUCCESS);
            return response;
        } catch (RuntimeException e) {
            emit(context, start, null, statusOf(e));
            throw e;
        }
    }

    @Override
    public void stream(ChatContext context, ChunkSink sink, Chain chain) {
        Instant start = clock.instant();
        Usage[] usage = {null}; // from the final usage chunk, if one arrives
        try {
            chain.stream(context, chunk -> {
                if (chunk.usage() != null) {
                    usage[0] = chunk.usage();
                }
                sink.accept(chunk);
            });
            emit(context, start, usage[0], UsageStatus.SUCCESS);
        } catch (RuntimeException e) {
            emit(context, start, usage[0], statusOf(e));
            throw e;
        }
    }

    static UsageStatus statusOf(RuntimeException e) {
        if (e instanceof RateLimitExceededException) {
            return UsageStatus.RATE_LIMITED;
        }
        if (e instanceof ProviderException) {
            return UsageStatus.PROVIDER_ERROR;
        }
        if (e instanceof ClientDisconnectedException) {
            return UsageStatus.CLIENT_DISCONNECTED;
        }
        return UsageStatus.ERROR;
    }

    private void emit(ChatContext context, Instant start, Usage usage, UsageStatus status) {
        try {
            Instant end = clock.instant();
            String cache = context.responseHeaders().asMap().get(CacheHeaders.X_CACHE);
            sink.publish(new UsageEvent(
                    UUID.randomUUID().toString(),
                    context.client().id(),
                    context.request().model(),
                    context.trace().provider(),
                    usage == null ? 0 : usage.promptTokens(),
                    usage == null ? 0 : usage.completionTokens(),
                    end.toEpochMilli() - start.toEpochMilli(),
                    cache == null ? UsageEvent.NO_CACHE_LOOKUP : cache,
                    status,
                    context.request().streaming(),
                    end));
        } catch (RuntimeException e) {
            // Accounting must never break a request (design note 37).
            log.warn("Could not record usage for client={}: {}", context.client().id(), e.toString());
        }
    }
}
