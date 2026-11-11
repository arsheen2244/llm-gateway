package dev.llmgateway.gateway.pipeline;

import java.util.List;
import java.util.function.Function;

import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;

/**
 * Runs interceptors in order, then the terminal step (the provider call).
 *
 * <p>Each chain object is immutable and knows its position. Calling
 * {@link #complete} runs the interceptor at {@code index} with a chain that
 * starts at {@code index + 1}. This is the same design as a servlet FilterChain
 * or OkHttp's interceptor chain.
 */
public final class InterceptorChain implements ChatInterceptor.Chain {

    private final List<ChatInterceptor> interceptors;
    private final int index;
    private final Function<ChatContext, ChatCompletionResponse> terminal;

    public InterceptorChain(List<ChatInterceptor> interceptors,
                            Function<ChatContext, ChatCompletionResponse> terminal) {
        this(List.copyOf(interceptors), 0, terminal);
    }

    private InterceptorChain(List<ChatInterceptor> interceptors, int index,
                             Function<ChatContext, ChatCompletionResponse> terminal) {
        this.interceptors = interceptors;
        this.index = index;
        this.terminal = terminal;
    }

    @Override
    public ChatCompletionResponse complete(ChatContext context) {
        if (index == interceptors.size()) {
            return terminal.apply(context);
        }
        return interceptors.get(index).complete(context, next());
    }

    private InterceptorChain next() {
        return new InterceptorChain(interceptors, index + 1, terminal);
    }
}
