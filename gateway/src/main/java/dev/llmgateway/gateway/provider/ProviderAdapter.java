package dev.llmgateway.gateway.provider;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.routing.ProviderConfig;

/**
 * Speaks one provider protocol. An adapter is stateless and shared by every
 * provider instance of its type. For example, "openai" serves both the real
 * OpenAI API and the mock, because the per-instance details (URL, key) arrive
 * in {@link ProviderConfig}.
 *
 * <p>Implementations translate between the gateway's OpenAI-shaped records and
 * the provider's own format, and throw only {@link ProviderException}, except
 * that exceptions thrown by the {@link ChunkSink} pass straight through.
 */
public interface ProviderAdapter {

    /** The protocol name used in config, e.g. "openai". */
    String type();

    ChatCompletionResponse complete(ProviderConfig provider, ChatCompletionRequest request);

    /**
     * Streams the response into {@code sink}, blocking until the provider finishes.
     * Returns normally only if the stream completed properly. A stream that
     * breaks or ends early throws {@link ProviderException} with reason
     * STREAM_INTERRUPTED. If the sink throws (the client went away), the
     * adapter closes the provider connection and lets the exception propagate.
     */
    void stream(ProviderConfig provider, ChatCompletionRequest request, ChunkSink sink);
}
