package dev.llmgateway.gateway.ratelimit;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatMessage;

/**
 * Guesses a request's total tokens before the call, so tokens can be reserved up front
 * (design note 19). The guess is corrected to the provider's actual usage afterwards.
 *
 * <ul>
 *   <li><b>Prompt:</b> characters ÷ 4, rounded up. About 4 characters per token is the
 *       usual rule of thumb for English text; it is cheap and needs no tokenizer.</li>
 *   <li><b>Completion:</b> the client's {@code max_tokens} if set (the reply cannot be
 *       longer), otherwise a configured default.</li>
 * </ul>
 */
public class TokenEstimator {

    private final int defaultCompletionTokens;

    public TokenEstimator(int defaultCompletionTokens) {
        this.defaultCompletionTokens = defaultCompletionTokens;
    }

    public long estimate(ChatCompletionRequest request) {
        return promptTokens(request) + completionTokens(request);
    }

    long promptTokens(ChatCompletionRequest request) {
        long characters = 0;
        for (ChatMessage message : request.messages()) {
            characters += message.content().length();
        }
        return (characters + 3) / 4; // divide by 4, rounding up
    }

    long completionTokens(ChatCompletionRequest request) {
        return request.maxTokens() != null ? request.maxTokens() : defaultCompletionTokens;
    }
}
