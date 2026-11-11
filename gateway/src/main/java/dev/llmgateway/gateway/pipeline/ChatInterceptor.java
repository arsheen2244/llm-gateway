package dev.llmgateway.gateway.pipeline;

import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;

/**
 * A step wrapped around every provider call: the plug-in point for rate
 * limiting (week 2), caching (week 4) and usage accounting (week 5).
 *
 * <p>An interceptor can act before the call, act after it, or skip it by
 * returning without calling {@code chain}. A cache hit would do that, and so
 * would a rate-limit rejection (by throwing). Interceptors are Spring beans and
 * run in {@code @Order} order.
 *
 * <p>The default method just passes the call along, so an interceptor only
 * overrides what it cares about.
 */
public interface ChatInterceptor {

    default ChatCompletionResponse complete(ChatContext context, Chain chain) {
        return chain.complete(context);
    }

    /** The rest of the pipeline: the remaining interceptors, then the provider. */
    interface Chain {
        ChatCompletionResponse complete(ChatContext context);
    }
}
