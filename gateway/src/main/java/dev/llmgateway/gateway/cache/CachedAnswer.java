package dev.llmgateway.gateway.cache;

/**
 * What is stored for one cached request: the answer text and enough to rebuild an
 * OpenAI-format response (or replay a stream) from it.
 *
 * @param content          the assistant's answer
 * @param finishReason     "stop" or "length"
 * @param model            the model name the provider reported
 * @param promptTokens     usage of the original call, reported again on hits (so clients see the
 *                         usual response shape); the rate limiter refunds hits separately
 * @param completionTokens see promptTokens
 */
public record CachedAnswer(String content, String finishReason, String model, int promptTokens,
                           int completionTokens) {
}
