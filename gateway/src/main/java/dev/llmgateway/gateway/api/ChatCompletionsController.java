package dev.llmgateway.gateway.api;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.pipeline.ChatPipeline;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The single public endpoint, compatible with OpenAI's Chat Completions API. */
@RestController
public class ChatCompletionsController {

    private final ChatPipeline pipeline;

    public ChatCompletionsController(ChatPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping(path = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatCompletionResponse chatCompletions(@Valid @RequestBody ChatCompletionRequest request) {
        return pipeline.complete(request);
    }
}
