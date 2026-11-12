package dev.llmgateway.gateway.pipeline;

import java.util.List;

import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderAdapter;

/**
 * Runs interceptors in order, then the provider adapter.
 *
 * <p>Each chain object is immutable and knows its position. Calling it runs
 * the interceptor at {@code index} with a chain that starts at
 * {@code index + 1}. This is the same design as a servlet FilterChain or
 * OkHttp's interceptor chain.
 */
public final class InterceptorChain implements ChatInterceptor.Chain {

    private final List<ChatInterceptor> interceptors;
    private final int index;
    private final ProviderAdapter adapter;

    public InterceptorChain(List<ChatInterceptor> interceptors, ProviderAdapter adapter) {
        this(List.copyOf(interceptors), 0, adapter);
    }

    private InterceptorChain(List<ChatInterceptor> interceptors, int index, ProviderAdapter adapter) {
        this.interceptors = interceptors;
        this.index = index;
        this.adapter = adapter;
    }

    @Override
    public ChatCompletionResponse complete(ChatContext context) {
        if (index == interceptors.size()) {
            return adapter.complete(context.provider(), context.request());
        }
        return interceptors.get(index).complete(context, next());
    }

    @Override
    public void stream(ChatContext context, ChunkSink sink) {
        if (index == interceptors.size()) {
            adapter.stream(context.provider(), context.request(), sink);
            return;
        }
        interceptors.get(index).stream(context, sink, next());
    }

    private InterceptorChain next() {
        return new InterceptorChain(interceptors, index + 1, adapter);
    }
}
