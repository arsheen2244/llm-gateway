package dev.llmgateway.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import dev.llmgateway.gateway.api.dto.Usage;
import dev.llmgateway.gateway.auth.ClientIdentity;
import dev.llmgateway.gateway.cache.CacheHeaders;
import dev.llmgateway.gateway.pipeline.ChatContext;
import dev.llmgateway.gateway.pipeline.ChatInterceptor;
import dev.llmgateway.gateway.pipeline.RequestOptions;
import dev.llmgateway.gateway.pipeline.ResponseHeaders;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import dev.llmgateway.gateway.ratelimit.RateLimitExceededException;
import dev.llmgateway.gateway.ratelimit.RateLimitInterceptor;
import dev.llmgateway.gateway.routing.ProviderConfig;
import dev.llmgateway.gateway.streaming.ClientDisconnectedException;
import dev.llmgateway.gateway.support.FakeClock;
import org.junit.jupiter.api.Test;

/** One event per request, whatever the outcome, and never a changed outcome (design note 37). */
class UsageInterceptorTest {

    private static final ClientIdentity CLIENT = new ClientIdentity("client-1", 10, 1000);

    private final FakeClock clock = new FakeClock();
    private final RecordingUsageSink sink = new RecordingUsageSink();
    private final UsageInterceptor interceptor = new UsageInterceptor(sink, clock);

    @Test
    void successfulCallRecordsTokensProviderCacheAndLatency() {
        ChatCompletionResponse response = interceptor.complete(context(false), provider(ctx -> {
            ctx.trace().attempting("openai");
            ctx.responseHeaders().set(CacheHeaders.X_CACHE, CacheHeaders.MISS);
            clock.advance(Duration.ofMillis(250));
            return response(Usage.of(12, 34));
        }));

        UsageEvent event = sink.only();
        assertThat(response.id()).isEqualTo("resp");
        assertThat(event.clientId()).isEqualTo("client-1");
        assertThat(event.model()).isEqualTo("gpt-x");
        assertThat(event.provider()).isEqualTo("openai");
        assertThat(event.promptTokens()).isEqualTo(12);
        assertThat(event.completionTokens()).isEqualTo(34);
        assertThat(event.latencyMs()).isEqualTo(250);
        assertThat(event.cache()).isEqualTo("miss");
        assertThat(event.status()).isEqualTo(UsageStatus.SUCCESS);
        assertThat(event.stream()).isFalse();
        assertThat(event.occurredAt()).isEqualTo(clock.instant());
        assertThat(event.eventId()).hasSize(36); // a UUID
    }

    @Test
    void everyRequestGetsItsOwnEventId() {
        interceptor.complete(context(false), provider(ctx -> response(null)));
        interceptor.complete(context(false), provider(ctx -> response(null)));

        assertThat(sink.events).extracting(UsageEvent::eventId).doesNotHaveDuplicates();
    }

    @Test
    void cacheHitHasNoProviderButKeepsTheCachedTokenCounts() {
        interceptor.complete(context(false), provider(ctx -> {
            ctx.responseHeaders().set(CacheHeaders.X_CACHE, CacheHeaders.EXACT);
            return response(Usage.of(5, 7));
        }));

        UsageEvent event = sink.only();
        assertThat(event.provider()).isNull();
        assertThat(event.cache()).isEqualTo("exact");
        assertThat(event.promptTokens()).isEqualTo(5);
        assertThat(event.completionTokens()).isEqualTo(7);
    }

    @Test
    void rateLimitedRequestIsRecordedAndTheRefusalPassesThrough() {
        RateLimitExceededException refusal = new RateLimitExceededException("slow down", null, Map.of());

        assertThatThrownBy(() -> interceptor.complete(context(false), provider(ctx -> {
            throw refusal;
        }))).isSameAs(refusal);

        UsageEvent event = sink.only();
        assertThat(event.status()).isEqualTo(UsageStatus.RATE_LIMITED);
        assertThat(event.cache()).isEqualTo(UsageEvent.NO_CACHE_LOOKUP);
        assertThat(event.provider()).isNull();
        assertThat(event.promptTokens()).isZero();
    }

    @Test
    void providerFailureIsRecordedWithTheProviderTried() {
        ProviderException failure = ProviderException.of("anthropic", Reason.TIMEOUT, "slow", null);

        assertThatThrownBy(() -> interceptor.complete(context(false), provider(ctx -> {
            ctx.trace().attempting("anthropic");
            throw failure;
        }))).isSameAs(failure);

        assertThat(sink.only().status()).isEqualTo(UsageStatus.PROVIDER_ERROR);
        assertThat(sink.only().provider()).isEqualTo("anthropic");
    }

    @Test
    void streamTakesTokensFromTheFinalUsageChunk() {
        List<ChatCompletionChunk> delivered = new ArrayList<>();

        interceptor.stream(context(true), delivered::add, streamingProvider(out -> {
            out.accept(chunk(null));
            out.accept(chunk(Usage.of(3, 9)));
        }));

        assertThat(delivered).hasSize(2);
        UsageEvent event = sink.only();
        assertThat(event.stream()).isTrue();
        assertThat(event.promptTokens()).isEqualTo(3);
        assertThat(event.completionTokens()).isEqualTo(9);
        assertThat(event.status()).isEqualTo(UsageStatus.SUCCESS);
    }

    @Test
    void clientLeavingMidStreamIsRecorded() {
        ChunkSink leavingClient = chunk -> {
            throw new ClientDisconnectedException(new IOException("broken pipe"));
        };

        assertThatThrownBy(() -> interceptor.stream(context(true), leavingClient,
                streamingProvider(out -> out.accept(chunk(null)))))
                .isInstanceOf(ClientDisconnectedException.class);

        assertThat(sink.only().status()).isEqualTo(UsageStatus.CLIENT_DISCONNECTED);
    }

    @Test
    void unexpectedFailureIsRecordedAsError() {
        assertThatThrownBy(() -> interceptor.complete(context(false), provider(ctx -> {
            throw new IllegalStateException("bug");
        }))).isInstanceOf(IllegalStateException.class);

        assertThat(sink.only().status()).isEqualTo(UsageStatus.ERROR);
    }

    @Test
    void aFailingSinkNeverChangesTheResponse() {
        UsageInterceptor withBrokenSink = new UsageInterceptor(event -> {
            throw new IllegalStateException("sink down");
        }, clock);

        ChatCompletionResponse response = withBrokenSink.complete(context(false), provider(ctx -> response(null)));

        assertThat(response.id()).isEqualTo("resp");
    }

    @Test
    void aFailingSinkNeverReplacesTheOriginalError() {
        UsageInterceptor withBrokenSink = new UsageInterceptor(event -> {
            throw new IllegalStateException("sink down");
        }, clock);
        ProviderException failure = ProviderException.of("p", Reason.IO_ERROR, "down", null);

        assertThatThrownBy(() -> withBrokenSink.complete(context(false), provider(ctx -> {
            throw failure;
        }))).isSameAs(failure);
    }

    @Test
    void runsBeforeRateLimiting() {
        assertThat(interceptor.getOrder()).isLessThan(RateLimitInterceptor.ORDER);
    }

    // ---- helpers ----

    private static ChatContext context(boolean stream) {
        ChatCompletionRequest request = new ChatCompletionRequest("gpt-x",
                List.of(new ChatMessage("user", "hi")), stream, null, null, null, null, null);
        return new ChatContext(CLIENT, request, new ProviderConfig("p", "openai", "http://x", "k"),
                new ResponseHeaders(), List.of(), RequestOptions.DEFAULTS);
    }

    private static ChatCompletionResponse response(Usage usage) {
        return new ChatCompletionResponse("resp", "chat.completion", 0, "gpt-x", List.of(), usage);
    }

    private static ChatCompletionChunk chunk(Usage usage) {
        return new ChatCompletionChunk("id", "chat.completion.chunk", 0, "gpt-x", List.of(), usage);
    }

    private static ChatInterceptor.Chain provider(Function<ChatContext, ChatCompletionResponse> answer) {
        return new ChatInterceptor.Chain() {
            @Override
            public ChatCompletionResponse complete(ChatContext context) {
                return answer.apply(context);
            }

            @Override
            public void stream(ChatContext context, ChunkSink sink) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ChatInterceptor.Chain streamingProvider(Consumer<ChunkSink> answer) {
        return new ChatInterceptor.Chain() {
            @Override
            public ChatCompletionResponse complete(ChatContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void stream(ChatContext context, ChunkSink sink) {
                answer.accept(sink);
            }
        };
    }
}
