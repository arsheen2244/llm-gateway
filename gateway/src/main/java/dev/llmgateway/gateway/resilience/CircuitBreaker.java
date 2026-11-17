package dev.llmgateway.gateway.resilience;

/**
 * A circuit breaker for one provider instance (design note 28). It stops sending traffic to a
 * provider that is failing, then carefully tries it again.
 *
 * <pre>
 *            >= minimumCalls in the window and failure rate >= threshold
 *   CLOSED ---------------------------------------------------------------> OPEN
 *     ^                                                                    |  |
 *     |  halfOpenTrialCalls successes                  openDuration passed |  |
 *     |                                     (next tryAcquirePermission())  |  |
 *     +------------------------ HALF_OPEN <--------------------------------+  |
 *                                   |                                         |
 *                                   +-------- any trial failure ------------->+
 * </pre>
 *
 * <b>How a caller uses it, for every provider call:</b>
 * <pre>
 *   if (!breaker.tryAcquirePermission()) { skip this provider }
 *   try { call; breaker.onSuccess(); }
 *   catch (provider-health failure) { breaker.onFailure(); }
 *   catch (anything else) { breaker.onIgnored(); }
 * </pre>
 * Exactly one of {@code onSuccess}, {@code onFailure} or {@code onIgnored} is called for
 * every permission that was granted.
 *
 * <p>Implementations must be thread-safe: many request threads use one breaker at once.
 */
public interface CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    /**
     * May a call be sent now?
     * <ul>
     *   <li>CLOSED: always true.</li>
     *   <li>OPEN: false, until {@code openDuration} has passed since it opened. Then this call
     *       moves the breaker to HALF_OPEN and is granted the first trial slot (true).</li>
     *   <li>HALF_OPEN: true while fewer than {@code halfOpenTrialCalls} trial slots are in use
     *       or already succeeded; false otherwise.</li>
     * </ul>
     */
    boolean tryAcquirePermission();

    /**
     * A permitted call succeeded.
     * CLOSED: record a success in the window. HALF_OPEN: count a trial success; when
     * {@code halfOpenTrialCalls} have succeeded, move to CLOSED with an empty window.
     * OPEN: ignore (a late result from before it opened).
     */
    void onSuccess();

    /**
     * A permitted call failed in a way that says the provider is unhealthy (timeout,
     * connection error, 5xx, provider 429).
     * CLOSED: record a failure; open if the window now meets both conditions.
     * HALF_OPEN: open again, for a new full {@code openDuration}.
     * OPEN: ignore (a late result; it must not extend the open period).
     */
    void onFailure();

    /**
     * A permitted call ended in a way that says nothing about the provider's health
     * (for example the client's own bad request, or a hedged call we cancelled).
     * Not counted anywhere. In HALF_OPEN it frees its trial slot for another call.
     */
    void onIgnored();

    State state();
}
