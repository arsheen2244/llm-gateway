package dev.llmgateway.gateway.pipeline;

import java.util.List;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.auth.ClientIdentity;
import dev.llmgateway.gateway.observability.LogFields;
import dev.llmgateway.gateway.provider.AdapterRegistry;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.provider.call.ProviderCaller;
import dev.llmgateway.gateway.routing.ModelRouter;
import dev.llmgateway.gateway.routing.ProviderConfig;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * The gateway's core flow: route the model to a provider, run the interceptors,
 * then call the provider's adapter. The controller deals with HTTP. This class
 * knows nothing about HTTP.
 */
@Service
public class ChatPipeline {

    private final ModelRouter router;
    private final AdapterRegistry adapters;
    private final List<ChatInterceptor> interceptors;
    private final ProviderCaller caller;

    /** Spring injects every ChatInterceptor bean, sorted by @Order. */
    public ChatPipeline(ModelRouter router, AdapterRegistry adapters, List<ChatInterceptor> interceptors,
                        ProviderCaller caller) {
        this.router = router;
        this.adapters = adapters;
        this.interceptors = List.copyOf(interceptors);
        this.caller = caller;
    }

    public ChatCompletionResponse complete(ChatCompletionRequest request, ClientIdentity client,
                                           ResponseHeaders headers) {
        ChatContext context = contextFor(request, client, headers);
        return chainFor(context).complete(context);
    }

    public void stream(ChatCompletionRequest request, ClientIdentity client, ResponseHeaders headers,
                       ChunkSink sink) {
        ChatContext context = contextFor(request, client, headers);
        chainFor(context).stream(context, sink);
    }

    private ChatContext contextFor(ChatCompletionRequest request, ClientIdentity client, ResponseHeaders headers) {
        // Recorded in the MDC so every later log line, including the access log, shows them.
        MDC.put(LogFields.MODEL, request.model());
        MDC.put(LogFields.STREAM, String.valueOf(request.streaming()));
        ProviderConfig provider = router.route(request.model());
        MDC.put(LogFields.PROVIDER, provider.name());
        if (!provider.hasApiKey()) {
            // Fail here with a clear message rather than sending an unauthenticated call upstream.
            throw ProviderException.of(provider.name(), ProviderException.Reason.NOT_CONFIGURED,
                    "No API key configured for provider '" + provider.name() + "'", null);
        }
        return new ChatContext(client, request, provider, headers);
    }

    private InterceptorChain chainFor(ChatContext context) {
        // Every provider call runs under the first-byte / total / stream deadlines (note 26).
        return new InterceptorChain(interceptors, caller.withDeadlines(adapters.forType(context.provider().type())));
    }
}
