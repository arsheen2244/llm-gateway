package dev.llmgateway.gateway.auth;

/**
 * An authenticated caller and its limits. Set by {@link ApiKeyAuthFilter} and carried
 * through the pipeline in {@code ChatContext}.
 */
public record ClientIdentity(String id, int requestsPerMinute, long tokensPerMinute) {

    /**
     * Name of the servlet request attribute that holds the identity. A string literal,
     * because annotation values ({@code @RequestAttribute}) must be compile-time constants.
     */
    public static final String REQUEST_ATTRIBUTE = "gateway.clientIdentity";
}
