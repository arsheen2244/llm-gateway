package dev.llmgateway.gateway.pipeline;

import java.util.List;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.auth.ClientIdentity;
import dev.llmgateway.gateway.observability.LogFields;
import dev.llmgateway.gateway.provider.ChunkSink;
import dev.llmgateway.gateway.provider.ProviderException;
import dev.llmgateway.gateway.routing.ModelRouter;
import dev.llmgateway.gateway.resilience.ResilientExecutor;
import dev.llmgateway.gateway.routing.ProviderConfig;
import dev.llmgateway.gateway.routing.ProviderTarget;
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
    private final List<ChatInterceptor> interceptors;
    private final ResilientExecutor executor;

    /** Spring injects every ChatInterceptor bean, sorted by @Order. */
    public ChatPipeline(ModelRouter router, List<ChatInterceptor> interceptors, ResilientExecutor executor) {
        this.router = router;
        this.interceptors = List.copyOf(interceptors);
        this.executor = executor;
    }

    public ChatCompletionResponse complete(ChatCompletionRequest request, ClientIdentity client,
                                           ResponseHeaders headers, RequestOptions options) {
        ChatContext context = contextFor(request, client, headers, options);
        return chainFor(context).complete(context);
    }

    public void stream(ChatCompletionRequest request, ClientIdentity client, ResponseHeaders headers,
                       RequestOptions options, ChunkSink sink) {
        ChatContext context = contextFor(request, client, headers, options);
        chainFor(context).stream(context, sink);
    }

    private ChatContext contextFor(ChatCompletionRequest request, ClientIdentity client, ResponseHeaders headers,
                                   RequestOptions options) {
        // Recorded in the MDC so every later log line, including the access log, shows them.
        MDC.put(LogFields.MODEL, request.model());
        MDC.put(LogFields.STREAM, String.valueOf(request.streaming()));
        List<ProviderTarget> targets = router.targets(request.model());
        ProviderConfig provider = targets.get(0).provider();
        MDC.put(LogFields.PROVIDER, provider.name());
        if (targets.stream().noneMatch(target -> target.provider().hasApiKey())) {
            // Fail here with a clear message rather than sending an unauthenticated call upstream.
            throw ProviderException.of(provider.name(), ProviderException.Reason.NOT_CONFIGURED,
                    "No API key configured for provider '" + provider.name() + "'", null);
        }
        return new ChatContext(client, request, provider, headers, targets, options);
    }

    /** Interceptors (rate limiting first), then the resilient executor (design note 27). */
    private InterceptorChain chainFor(ChatContext context) {
        return new InterceptorChain(interceptors, executor);
    }
}
