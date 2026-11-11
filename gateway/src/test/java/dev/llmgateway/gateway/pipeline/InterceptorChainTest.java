package dev.llmgateway.gateway.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import org.junit.jupiter.api.Test;

class InterceptorChainTest {

    private static final ChatCompletionResponse PROVIDER_RESPONSE =
            new ChatCompletionResponse("from-provider", "chat.completion", 0, "m", List.of(), null);
    private static final ChatCompletionResponse CACHED_RESPONSE =
            new ChatCompletionResponse("from-cache", "chat.completion", 0, "m", List.of(), null);

    private final List<String> calls = new ArrayList<>();

    @Test
    void interceptorsWrapTheProviderCallInOrder() {
        InterceptorChain chain = new InterceptorChain(
                List.of(recording("A"), recording("B")),
                ctx -> { calls.add("provider"); return PROVIDER_RESPONSE; });

        ChatCompletionResponse response = chain.complete(null);

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
        InterceptorChain chain = new InterceptorChain(List.of(cache),
                ctx -> { calls.add("provider"); return PROVIDER_RESPONSE; });

        assertThat(chain.complete(null)).isSameAs(CACHED_RESPONSE);
        assertThat(calls).isEmpty();
    }

    @Test
    void emptyChainCallsProviderDirectly() {
        InterceptorChain chain = new InterceptorChain(List.of(), ctx -> PROVIDER_RESPONSE);

        assertThat(chain.complete(null)).isSameAs(PROVIDER_RESPONSE);
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
}
