package dev.llmgateway.gateway.pipeline;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.routing.ProviderConfig;

/**
 * What an interceptor can see about the current call. Later weeks add fields
 * here, such as the calling client's identity for rate limiting (week 2).
 *
 * @param request  the client's request
 * @param provider the provider instance the router picked
 */
public record ChatContext(ChatCompletionRequest request, ProviderConfig provider) {
}
