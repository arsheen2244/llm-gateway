package dev.llmgateway.gateway.usage;

/**
 * Where usage events go. Called on the request's thread once the request is done, so an
 * implementation must return at once and never throw: accounting must not slow or fail a
 * request (design note 37).
 */
@FunctionalInterface
public interface UsageSink {

    void publish(UsageEvent event);
}
