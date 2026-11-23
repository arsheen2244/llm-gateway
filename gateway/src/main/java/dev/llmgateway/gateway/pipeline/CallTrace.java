package dev.llmgateway.gateway.pipeline;

/**
 * Facts about one request that only become known deep inside the pipeline, recorded for the
 * steps around it (usage accounting reads them after the call returns).
 *
 * <p>One instance per request. The executor writes it on the request's thread before each
 * provider attempt, and the usage interceptor reads it on the same thread afterwards.
 */
public final class CallTrace {

    private volatile String provider;
    private volatile String upstreamModel;

    /**
     * The latest attempt: after a success, the one that answered. A failover provider can be
     * sent a different model name than the client asked for (design note 25).
     */
    public void attempting(String providerName, String model) {
        this.provider = providerName;
        this.upstreamModel = model;
    }

    /** Null when no provider was called (rate-limited, served from the cache). */
    public String provider() {
        return provider;
    }

    /** The model name sent to that provider; null when no provider was called. */
    public String upstreamModel() {
        return upstreamModel;
    }
}
