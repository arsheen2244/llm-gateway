package dev.llmgateway.gateway.pipeline;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.auth.ClientIdentity;
import dev.llmgateway.gateway.routing.ProviderConfig;

/**
 * What an interceptor can see about the current call.
 *
 * @param client          the authenticated caller and its limits (used by rate limiting)
 * @param request         the client's request
 * @param provider        the provider instance the router picked
 * @param responseHeaders headers interceptors want sent back to the client
 */
public record ChatContext(ClientIdentity client, ChatCompletionRequest request, ProviderConfig provider,
                          ResponseHeaders responseHeaders) {
}
