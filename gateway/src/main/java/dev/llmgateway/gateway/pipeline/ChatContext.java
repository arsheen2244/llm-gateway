package dev.llmgateway.gateway.pipeline;

import java.util.List;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.auth.ClientIdentity;
import dev.llmgateway.gateway.routing.ProviderConfig;
import dev.llmgateway.gateway.routing.ProviderTarget;

/**
 * What an interceptor can see about the current call.
 *
 * @param client          the authenticated caller and its limits (used by rate limiting)
 * @param request         the client's request
 * @param provider        the first provider in the route (for logs and simple callers)
 * @param responseHeaders headers interceptors want sent back to the client
 * @param targets         every provider to try, in failover order (design note 25)
 * @param options         choices the client made through headers (e.g. cache bypass)
 * @param trace           what happened inside the pipeline (e.g. which provider answered)
 */
public record ChatContext(ClientIdentity client, ChatCompletionRequest request, ProviderConfig provider,
                          ResponseHeaders responseHeaders, List<ProviderTarget> targets,
                          RequestOptions options, CallTrace trace) {

    /** A context with a fresh trace (tests and callers that don't read it). */
    public ChatContext(ClientIdentity client, ChatCompletionRequest request, ProviderConfig provider,
                       ResponseHeaders responseHeaders, List<ProviderTarget> targets, RequestOptions options) {
        this(client, request, provider, responseHeaders, targets, options, new CallTrace());
    }
}
