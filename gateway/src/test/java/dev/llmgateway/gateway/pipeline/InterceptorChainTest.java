package dev.llmgateway.gateway.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderAdapter;
import dev.llmgateway.gateway.routing.ProviderConfig;
import org.junit.jupiter.api.Test;

class InterceptorChainTest {

    private static final ChatCompletionResponse PROVIDER_RESPONSE =
            new ChatCompletionResponse("from-provider", "chat.completion", 0, "m", List.of(), null);
    private static final ChatCompletionResponse CACHED_RESPONSE =
            new ChatCompletionResponse("from-cache", "chat.completion", 0, "m", List.of(), null);
    private static final ChatCompletionChunk CHUNK =
            new ChatCompletionChunk("c", "chat.completion.chunk", 0, "m", List.of(), null);
    private static final ChatContext CONTEXT =
            new ChatContext(null, null, new ProviderConfig("p", "fake", "http://x", "k"));

    private final List<String> calls = new ArrayList<>();
    private final ProviderAdapter provider = new FakeAdapter();

    @Test
    void interceptorsWrapTheProviderCallInOrder() {
        InterceptorChain chain = new InterceptorChain(List.of(recording("A"), recording("B")), provider);

        ChatCompletionResponse response = chain.complete(CONTEXT);

        assertThat(response).isSameAs(PROVIDER_RESPONSE);
        assertThat(calls).containsExactly("A:before", "B:before", "provider", "B:after", "A:after");
    }

    @Test
    void interceptorCanShortCircuitWithoutCallingProvider() {
        ChatInterceptor cache = new ChatInterceptor() {
            @Override
            public ChatCompletionResponse complete(ChatContext context, Chain chain) {
                return CACHED_RESPONSE; // e.g. a cache hit: never calls chain
            }
        };

        assertThat(new InterceptorChain(List.of(cache), provider).complete(CONTEXT)).isSameAs(CACHED_RESPONSE);
        assertThat(calls).isEmpty();
    }

    @Test
    void streamingInterceptorCanObserveEveryChunkByWrappingTheSink() {
        List<String> seen = new ArrayList<>();
        ChatInterceptor observer = new ChatInterceptor() {
            @Override
            public void stream(ChatContext context, ChunkSink sink, Chain chain) {
                chain.stream(context, chunk -> {
                    seen.add("observed " + chunk.id());
                    sink.accept(chunk);
                });
            }
        };
        List<ChatCompletionChunk> delivered = new ArrayList<>();

        new InterceptorChain(List.of(observer), provider).stream(CONTEXT, delivered::add);

        assertThat(seen).containsExactly("observed c", "observed c");
        assertThat(delivered).hasSize(2);
    }

    @Test
    void interceptorWithOnlyDefaultsPassesEverythingThrough() {
        ChatInterceptor passive = new ChatInterceptor() { };
        List<ChatCompletionChunk> delivered = new ArrayList<>();
        InterceptorChain chain = new InterceptorChain(List.of(passive), provider);

        assertThat(chain.complete(CONTEXT)).isSameAs(PROVIDER_RESPONSE);
        chain.stream(CONTEXT, delivered::add);
        assertThat(delivered).hasSize(2);
    }

    private ChatInterceptor recording(String name) {
        return new ChatInterceptor() {
            @Override
            public ChatCompletionResponse complete(ChatContext context, Chain chain) {
                calls.add(name + ":before");
                ChatCompletionResponse response = chain.complete(context);
                calls.add(name + ":after");
                return response;
            }
        };
    }

    private class FakeAdapter implements ProviderAdapter {
        @Override
        public String type() {
            return "fake";
        }

        @Override
        public ChatCompletionResponse complete(ProviderConfig p, ChatCompletionRequest request) {
            calls.add("provider");
            return PROVIDER_RESPONSE;
        }

        @Override
        public void stream(ProviderConfig p, ChatCompletionRequest request, ChunkSink sink) {
            sink.accept(CHUNK);
            sink.accept(CHUNK);
        }
    }
}
