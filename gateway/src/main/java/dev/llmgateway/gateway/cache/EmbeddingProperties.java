package dev.llmgateway.gateway.cache;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code gateway.cache.embedding}: where the embedding service is (design note 34).
 *
 * @param url        base URL of the text-embeddings-inference service
 * @param timeout    connect + read limit for one call. Short: a slow embedding must not slow
 *                   down the request it was meant to speed up
 * @param dimensions vector length the model produces (bge-small-en-v1.5: 384). Checked on every
 *                   answer, because the vector index is built for exactly this length
 */
@ConfigurationProperties(prefix = "gateway.cache.embedding")
public record EmbeddingProperties(
        @DefaultValue("http://localhost:8082") URI url,
        @DefaultValue("500ms") Duration timeout,
        @DefaultValue("384") int dimensions) {
}
