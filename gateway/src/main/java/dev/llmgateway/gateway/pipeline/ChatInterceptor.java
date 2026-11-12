package dev.llmgateway.gateway.pipeline;

import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.provider.ChunkSink;

/**
 * A step wrapped around every provider call: the plug-in point for rate
 * limiting (week 2), caching (week 4) and usage accounting (week 5).
 *
 * <p>An interceptor can act before the call, act after it, or skip it by
 * returning without calling {@code chain}. A cache hit would do that, and so
 * would a rate-limit rejection (by throwing). For streams, an interceptor can
 * also wrap the {@link ChunkSink} to see every chunk as it passes.
 * Interceptors are Spring beans and run in {@code @Order} order.
 *
 * <p>The default methods just pass the call along, so an interceptor only
 * overrides what it cares about.
 */
public interface ChatInterceptor {

    default ChatCompletionResponse complete(ChatContext context, Chain chain) {
        return chain.complete(context);
    }

    default void stream(ChatContext context, ChunkSink sink, Chain chain) {
        chain.stream(context, sink);
    }

    /** The rest of the pipeline: the remaining interceptors, then the provider. */
    interface Chain {
        ChatCompletionResponse complete(ChatContext context);

        void stream(ChatContext context, ChunkSink sink);
    }
}
