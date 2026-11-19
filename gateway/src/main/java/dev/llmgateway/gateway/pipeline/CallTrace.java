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

    /** The provider of the latest attempt: after a success, the one that answered. */
    public void attempting(String providerName) {
        this.provider = providerName;
    }

    /** Null when no provider was called (rate-limited, served from the cache). */
    public String provider() {
        return provider;
    }
}
