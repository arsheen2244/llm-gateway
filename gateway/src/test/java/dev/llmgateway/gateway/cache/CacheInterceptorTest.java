package dev.llmgateway.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import dev.llmgateway.gateway.api.dto.Choice;
import dev.llmgateway.gateway.api.dto.ChunkChoice;
import dev.llmgateway.gateway.api.dto.Delta;
import dev.llmgateway.gateway.api.dto.Usage;
import dev.llmgateway.gateway.auth.ClientIdentity;
import dev.llmgateway.gateway.pipeline.ChatContext;
import dev.llmgateway.gateway.pipeline.ChatInterceptor;
import dev.llmgateway.gateway.pipeline.RequestOptions;
import dev.llmgateway.gateway.pipeline.ResponseHeaders;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.ProviderException.Reason;
import dev.llmgateway.gateway.support.FakeClock;
import dev.llmgateway.gateway.routing.ProviderConfig;
import org.junit.jupiter.api.Test;

/** The exact-match cache's decisions (design note 32), with an in-memory store and a fake provider. */
class CacheInterceptorTest {

    private static final ChatCompletionRequest CACHEABLE = request(0.0, "What is Redis?");

    private final FakeClock clock = new FakeClock();
    private final InMemoryCacheStore store = new InMemoryCacheStore(clock);
    private final CacheInterceptor cache = new CacheInterceptor(store,
            new CacheProperties(true, CacheProperties.Store.IN_MEMORY, Duration.ofMinutes(10), 0.3, true, 0.95), clock);
    private final FakeProvider provider = new FakeProvider();

    @Test
    void firstRequestMissesAndIsStoredSecondIsAnExactHit() {
        ChatContext first = context("team-a", CACHEABLE);
        ChatCompletionResponse answer = cache.complete(first, provider);
        assertThat(first.responseHeaders().asMap()).containsEntry("X-Cache", "miss");

        ChatContext second = context("team-a", CACHEABLE);
        ChatCompletionResponse cached = cache.complete(second, provider);

        assertThat(second.responseHeaders().asMap()).containsEntry("X-Cache", "exact");
        assertThat(provider.calls).isEqualTo(1);
        assertThat(cached.choices().get(0).message().content()).isEqualTo(answer.choices().get(0).message().content());
        assertThat(cached.usage()).isEqualTo(Usage.of(5, 7)); // the original call's usage
        assertThat(cached.id()).startsWith("chatcmpl-cache-");
    }

    @Test
    void anotherClientNeverGetsTheAnswer() {
        cache.complete(context("team-a", CACHEABLE), provider);

        ChatContext otherClient = context("team-b", CACHEABLE);
        cache.complete(otherClient, provider);

        assertThat(otherClient.responseHeaders().asMap()).containsEntry("X-Cache", "miss");
        assertThat(provider.calls).isEqualTo(2);
    }

    @Test
    void clientCanBypassTheCache() {
        cache.complete(context("team-a", CACHEABLE), provider);

        ChatContext bypass = context("team-a", CACHEABLE, new RequestOptions(true));
        cache.complete(bypass, provider);

        assertThat(bypass.responseHeaders().asMap()).containsEntry("X-Cache", "bypass");
        assertThat(provider.calls).isEqualTo(2);
    }

    @Test
    void ineligibleRequestsBypassAndAreNotStored() {
        ChatCompletionRequest hot = request(0.9, "What is Redis?");
        cache.complete(context("team-a", hot), provider);

        ChatContext again = context("team-a", hot);
        cache.complete(again, provider);

        assertThat(again.responseHeaders().asMap()).containsEntry("X-Cache", "bypass");
        assertThat(provider.calls).isEqualTo(2);
    }

    @Test
    void entriesExpireAfterTheTtl() {
        cache.complete(context("team-a", CACHEABLE), provider);
        clock.advance(Duration.ofMinutes(10));

        ChatContext later = context("team-a", CACHEABLE);
        cache.complete(later, provider);

        assertThat(later.responseHeaders().asMap()).containsEntry("X-Cache", "miss");
    }

    @Test
    void failedCallsAreNotCached() {
        provider.failNext = true;
        assertThatThrownBy(() -> cache.complete(context("team-a", CACHEABLE), provider))
                .isInstanceOf(ProviderException.class);

        ChatContext retry = context("team-a", CACHEABLE);
        cache.complete(retry, provider);

        assertThat(retry.responseHeaders().asMap()).containsEntry("X-Cache", "miss");
    }

    @Test
    void streamedAnswerIsCollectedAndReplayedAsAStreamOnTheNextRequest() {
        List<ChatCompletionChunk> firstStream = new ArrayList<>();
        cache.stream(context("team-a", CACHEABLE), firstStream::add, provider);

        ChatContext second = context("team-a", CACHEABLE);
        List<ChatCompletionChunk> replayed = new ArrayList<>();
        cache.stream(second, replayed::add, provider);

        assertThat(second.responseHeaders().asMap()).containsEntry("X-Cache", "exact");
        assertThat(provider.calls).isEqualTo(1);
        assertThat(text(replayed)).isEqualTo("Redis is an in-memory data store.");
        assertThat(replayed.get(0).choices().get(0).delta().role()).isEqualTo("assistant");
        assertThat(replayed).anySatisfy(c -> assertThat(c.choices()).isNotEmpty()
                .allSatisfy(choice -> assertThat(choice.finishReason()).isIn("stop", null)));
        assertThat(replayed.getLast().usageOnly()).isTrue();
    }

    @Test
    void anAnswerCachedFromAStreamServesANormalRequestAndViceVersa() {
        cache.stream(context("team-a", CACHEABLE), chunk -> { }, provider);

        ChatContext plain = context("team-a", CACHEABLE);
        ChatCompletionResponse response = cache.complete(plain, provider);

        assertThat(plain.responseHeaders().asMap()).containsEntry("X-Cache", "exact");
        assertThat(response.choices().get(0).message().content()).isEqualTo("Redis is an in-memory data store.");
    }

    @Test
    void brokenStreamsAreNotCached() {
        provider.breakStream = true;
        assertThatThrownBy(() -> cache.stream(context("team-a", CACHEABLE), chunk -> { }, provider))
                .isInstanceOf(ProviderException.class);

        ChatContext retry = context("team-a", CACHEABLE);
        cache.stream(retry, chunk -> { }, provider);

        assertThat(retry.responseHeaders().asMap()).containsEntry("X-Cache", "miss");
    }

    @Test
    void storeFailuresNeverFailTheRequest() {
        CacheStore broken = new CacheStore() {
            @Override
            public java.util.Optional<CachedAnswer> getExact(String key) {
                throw new IllegalStateException("Redis down");
            }

            @Override
            public void putExact(String key, CachedAnswer answer, Duration ttl) {
                throw new IllegalStateException("Redis down");
            }
        };
        CacheInterceptor withBrokenStore = new CacheInterceptor(broken,
                new CacheProperties(true, CacheProperties.Store.REDIS, Duration.ofMinutes(10), 0.3, true, 0.95), clock);

        ChatCompletionResponse response = withBrokenStore.complete(context("team-a", CACHEABLE), provider);

        assertThat(response.choices()).isNotEmpty();
    }

    // ---- helpers ----

    private static ChatCompletionRequest request(Double temperature, String userText) {
        return new ChatCompletionRequest("m", List.of(new ChatMessage("user", userText)), null, null,
                temperature, null, null, null);
    }

    private static ChatContext context(String clientId, ChatCompletionRequest request) {
        return context(clientId, request, RequestOptions.DEFAULTS);
    }

    private static ChatContext context(String clientId, ChatCompletionRequest request, RequestOptions options) {
        return new ChatContext(new ClientIdentity(clientId, 100, 100_000), request,
                new ProviderConfig("p", "openai", "http://x", "k"), new ResponseHeaders(), List.of(), options);
    }

    private static String text(List<ChatCompletionChunk> chunks) {
        StringBuilder text = new StringBuilder();
        for (ChatCompletionChunk chunk : chunks) {
            for (ChunkChoice choice : chunk.choices()) {
                if (choice.delta() != null && choice.delta().content() != null) {
                    text.append(choice.delta().content());
                }
            }
        }
        return text.toString();
    }

    /** Answers "Redis is an in-memory data store." normally or as a stream, and counts calls. */
    private static final class FakeProvider implements ChatInterceptor.Chain {
        int calls;
        boolean failNext;
        boolean breakStream;

        @Override
        public ChatCompletionResponse complete(ChatContext context) {
            calls++;
            if (failNext) {
                failNext = false;
                throw ProviderException.errorResponse("p", 503, "down");
            }
            return new ChatCompletionResponse("id", "chat.completion", 0, "m",
                    List.of(new Choice(0, new ChatMessage("assistant", "Redis is an in-memory data store."), "stop")),
                    Usage.of(5, 7));
        }

        @Override
        public void stream(ChatContext context, ChunkSink sink) {
            calls++;
            Consumer<String> content = text -> sink.accept(chunk(new Delta(null, text), null));
            sink.accept(chunk(new Delta("assistant", ""), null));
            content.accept("Redis is ");
            if (breakStream) {
                breakStream = false;
                throw ProviderException.of("p", Reason.STREAM_INTERRUPTED, "cut", null);
            }
            content.accept("an in-memory data store.");
            sink.accept(chunk(new Delta(null, null), "stop"));
            sink.accept(new ChatCompletionChunk("id", "chat.completion.chunk", 0, "m", List.of(), Usage.of(5, 7)));
        }

        private static ChatCompletionChunk chunk(Delta delta, String finishReason) {
            return new ChatCompletionChunk("id", "chat.completion.chunk", 0, "m",
                    List.of(new ChunkChoice(0, delta, finishReason)), null);
        }
    }
}
