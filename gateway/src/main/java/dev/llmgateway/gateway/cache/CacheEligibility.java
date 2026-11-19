package dev.llmgateway.gateway.cache;

import java.util.List;

import dev.llmgateway.gateway.api.dto.ChatCompletionRequest;
import dev.llmgateway.gateway.api.dto.ChatMessage;

/**
 * Which requests may use the cache (owner's choice; design note 32).
 *
 * <ul>
 *   <li><b>Exact layer:</b> the request explicitly sets {@code temperature} at or below the
 *       configured maximum (default 0.3). An unset temperature means the provider's default
 *       (1.0 at OpenAI), i.e. the caller didn't ask for a stable answer.</li>
 *   <li><b>Semantic layer:</b> additionally single-turn: optional system messages, then exactly
 *       one user message. Matching whole conversations "by meaning" is far riskier: two chats
 *       can end with the same question after very different context.</li>
 * </ul>
 */
public final class CacheEligibility {

    private final double maxTemperature;

    public CacheEligibility(double maxTemperature) {
        this.maxTemperature = maxTemperature;
    }

    public boolean exactEligible(ChatCompletionRequest request) {
        return request.temperature() != null && request.temperature() <= maxTemperature;
    }

    public boolean semanticEligible(ChatCompletionRequest request) {
        return exactEligible(request) && singleTurn(request.messages());
    }

    static boolean singleTurn(List<ChatMessage> messages) {
        int users = 0;
        for (ChatMessage message : messages) {
            switch (message.role()) {
                case "system" -> {
                    if (users > 0) {
                        return false; // a system message after the user's turn
                    }
                }
                case "user" -> users++;
                default -> {
                    return false; // an assistant turn means this is a conversation
                }
            }
        }
        return users == 1;
    }
}
