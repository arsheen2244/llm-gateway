package dev.llmgateway.gateway.ratelimit;

import java.time.Duration;

/**
 * Proof of an admitted request: what to adjust when the actual token usage is known.
 *
 * @param clientId       whose window the entry lives in
 * @param id             unique id of the window entry
 * @param reservedTokens the estimate that was reserved
 * @param window         the window the entry was admitted into (needed to tell if it expired)
 */
public record Reservation(String clientId, String id, long reservedTokens, Duration window) {
}
