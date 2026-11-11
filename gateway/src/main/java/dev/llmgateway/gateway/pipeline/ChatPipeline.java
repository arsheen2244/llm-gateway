package dev.llmgateway.gateway.pipeline;

import java.util.List;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.provider.AdapterRegistry;
import dev.llmgateway.gateway.provider.ProviderAdapter;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.routing.ModelRouter;
import dev.llmgateway.gateway.routing.ProviderConfig;
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

    /** Spring injects every ChatInterceptor bean, sorted by @Order (empty in week 1). */
    public ChatPipeline(ModelRouter router, AdapterRegistry adapters, List<ChatInterceptor> interceptors) {
        this.router = router;
        this.adapters = adapters;
        this.interceptors = List.copyOf(interceptors);
    }

    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        ChatContext context = new ChatContext(request, resolve(request.model()));
        ProviderAdapter adapter = adapters.forType(context.provider().type());
        return new InterceptorChain(interceptors, ctx -> adapter.complete(ctx.provider(), ctx.request()))
                .complete(context);
    }

    private ProviderConfig resolve(String model) {
        ProviderConfig provider = router.route(model);
        if (!provider.hasApiKey()) {
            // Fail here with a clear message rather than sending an unauthenticated call upstream.
            throw ProviderException.of(provider.name(), ProviderException.Reason.NOT_CONFIGURED,
                    "No API key configured for provider '" + provider.name() + "'", null);
        }
        return provider;
    }
}
