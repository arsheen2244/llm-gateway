package dev.llmgateway.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import dev.llmgateway.gateway.api.dto.Usage;
import dev.llmgateway.gateway.auth.ClientIdentity;
import dev.llmgateway.gateway.pipeline.ChatContext;
import dev.llmgateway.gateway.pipeline.ChatInterceptor;
import dev.llmgateway.gateway.pipeline.ResponseHeaders;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import dev.llmgateway.gateway.routing.ProviderConfig;
import org.junit.jupiter.api.Test;

/**
 * The interceptor's decisions, with a real in-memory limiter and a fake provider.
 * Every request here is estimated at 1 (prompt "abcd") + 99 (max_tokens) = 100 tokens,
 * against a limit of 5 requests and 200 tokens per minute.
 */
class RateLimitInterceptorTest {

    private static final ClientIdentity CLIENT = new ClientIdentity("c", 5, 200);
    private static final ChatCompletionRequest REQUEST = new ChatCompletionRequest("m",
            List.of(new ChatMessage("user", "abcd")), null, null, null, 99, null, null);

    private final InMemoryRateLimiterTest.MutableClock clock = new InMemoryRateLimiterTest.MutableClock();
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);
    private final RateLimitProperties properties =
            new RateLimitProperties(true, RateLimitProperties.Backend.IN_MEMORY, Duration.ofMinutes(1), 1000);
    private final RateLimitInterceptor interceptor =
            new RateLimitInterceptor(limiter, new TokenEstimator(1000), properties);

    @Test
    void allowedRequestReachesTheProviderAndPublishesHeaders() {
        ChatContext context = context();

        interceptor.complete(context, provider(() -> response(null)));

        assertThat(context.responseHeaders().asMap())
                .containsEntry("x-ratelimit-remaining-requests", "4")
                .containsEntry("x-ratelimit-remaining-tokens", "100");
    }

    @Test
    void refusedRequestNeverReachesTheProvider() {
        interceptor.complete(context(), provider(() -> response(null)));
        interceptor.complete(context(), provider(() -> response(null))); // 200 of 200 tokens reserved
        List<String> calls = new ArrayList<>();

        assertThatThrownBy(() -> interceptor.complete(context(), provider(() -> {
            calls.add("provider");
            return response(null);
        })))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.code()).isEqualTo("rate_limit_exceeded");
                    assertThat(e.headers()).containsKey("Retry-After");
                });
        assertThat(calls).isEmpty();
    }

    @Test
    void reportedUsageReplacesTheEstimate() {
        interceptor.complete(context(), provider(() -> response(Usage.of(10, 20))));

        assertThat(remainingTokens()).isEqualTo(200 - 30);
    }

    @Test
    void missingUsageKeepsTheEstimate() {
        interceptor.complete(context(), provider(() -> response(null)));

        assertThat(remainingTokens()).isEqualTo(200 - 100);
    }

    @Test
    void providerErrorBeforeAnyOutputRefundsTokensButStillCountsTheRequest() {
        assertThatThrownBy(() -> interceptor.complete(context(), provider(() -> {
            throw ProviderException.errorResponse("p", 500, "boom");
        }))).isInstanceOf(ProviderException.class);

        RateLimitDecision next = limiter.tryAcquire("c", limits(), 0);
        assertThat(next.remainingTokens()).isEqualTo(200);
        assertThat(next.remainingRequests()).isEqualTo(5 - 2);
    }

    @Test
    void brokenStreamKeepsTheEstimateBecauseSomethingMayHaveBeenBilled() {
        assertThatThrownBy(() -> interceptor.stream(context(), chunk -> { }, streamingProvider(sink -> {
            sink.accept(contentChunk());
            throw ProviderException.of("p", Reason.STREAM_INTERRUPTED, "cut", null);
        }))).isInstanceOf(ProviderException.class);

        assertThat(remainingTokens()).isEqualTo(200 - 100);
    }

    @Test
    void streamUsesTheFinalUsageChunkAndStillDeliversEveryChunk() {
        List<ChatCompletionChunk> delivered = new ArrayList<>();

        interceptor.stream(context(), delivered::add, streamingProvider(sink -> {
            sink.accept(contentChunk());
            sink.accept(new ChatCompletionChunk("id", "chat.completion.chunk", 0, "m", List.of(), Usage.of(5, 7)));
        }));

        assertThat(delivered).hasSize(2);
        assertThat(remainingTokens()).isEqualTo(200 - 12);
    }

    @Test
    void failingReconcileDoesNotFailTheRequest() {
        RateLimiter brokenReconcile = new RateLimiter() {
            @Override
            public RateLimitDecision tryAcquire(String clientId, Limits limits, long estimatedTokens) {
                return limiter.tryAcquire(clientId, limits, estimatedTokens);
            }

            @Override
            public boolean reconcile(Reservation reservation, long actualTokens) {
                throw new IllegalStateException("Redis went away");
            }
        };
        RateLimitInterceptor withBrokenReconcile =
                new RateLimitInterceptor(brokenReconcile, new TokenEstimator(1000), properties);

        ChatCompletionResponse response =
                withBrokenReconcile.complete(context(), provider(() -> response(Usage.of(1, 1))));

        assertThat(response).isNotNull();
    }

    // ---- helpers ----

    private ChatContext context() {
        return new ChatContext(CLIENT, REQUEST, new ProviderConfig("p", "openai", "http://x", "k"),
                new ResponseHeaders());
    }

    private Limits limits() {
        return new Limits(CLIENT.requestsPerMinute(), CLIENT.tokensPerMinute(), Duration.ofMinutes(1));
    }

    /** Tokens left right now (a zero-token probe reads the window without changing tokens). */
    private long remainingTokens() {
        return limiter.tryAcquire("c", limits(), 0).remainingTokens();
    }

    private static ChatCompletionResponse response(Usage usage) {
        return new ChatCompletionResponse("id", "chat.completion", 0, "m", List.of(), usage);
    }

    private static ChatCompletionChunk contentChunk() {
        return new ChatCompletionChunk("id", "chat.completion.chunk", 0, "m", List.of(), null);
    }

    private static ChatInterceptor.Chain provider(Supplier<ChatCompletionResponse> answer) {
        return new ChatInterceptor.Chain() {
            @Override
            public ChatCompletionResponse complete(ChatContext context) {
                return answer.get();
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
