package dev.llmgateway.gateway.provider;

import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;

/**
 * Receives stream chunks one at a time, as they arrive from the provider.
 *
 * <p>This is a "push" design: the adapter reads the provider's stream and
 * pushes each chunk here. Interceptors can wrap a sink to observe chunks, for
 * example to count tokens or record a response for the cache.
 */
@FunctionalInterface
public interface ChunkSink {

    void accept(ChatCompletionChunk chunk);
}
