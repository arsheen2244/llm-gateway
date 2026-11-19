package dev.llmgateway.gateway.api;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.auth.ClientIdentity;
import dev.llmgateway.gateway.pipeline.ChatPipeline;
import dev.llmgateway.gateway.pipeline.RequestOptions;
import dev.llmgateway.gateway.pipeline.ResponseHeaders;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The single public endpoint, compatible with OpenAI's Chat Completions API. */
@RestController
public class ChatCompletionsController {

    private final ChatPipeline pipeline;
    private final StreamingChatHandler streamingHandler;

    public ChatCompletionsController(ChatPipeline pipeline, StreamingChatHandler streamingHandler) {
        this.pipeline = pipeline;
        this.streamingHandler = streamingHandler;
    }

    /**
     * One URL serves both modes, as in OpenAI's API: {@code "stream": true}
     * selects SSE. For streams, the handler writes the response itself and this
     * method returns null, which tells Spring there is no body left to write.
     *
     * <p>{@code client} was attached by {@code ApiKeyAuthFilter}; requests without a
     * valid key never reach this method.
     */
    @PostMapping(path = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatCompletionResponse chatCompletions(
            @Valid @RequestBody ChatCompletionRequest request,
            @RequestAttribute(ClientIdentity.REQUEST_ATTRIBUTE) ClientIdentity client,
            @RequestHeader(name = "Cache-Control", required = false) String cacheControl,
            HttpServletResponse response) {
        RequestOptions options = RequestOptions.fromCacheControl(cacheControl);
        if (request.streaming()) {
            streamingHandler.handle(request, client, options, response);
            return null;
        }
        ResponseHeaders headers = new ResponseHeaders();
        ChatCompletionResponse body = pipeline.complete(request, client, headers, options);
        // Spring writes the body after this method returns, so headers set now still go out.
        headers.asMap().forEach(response::setHeader);
        return body;
    }
}
