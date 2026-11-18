package dev.llmgateway.gateway.cache;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the cache into the interceptor chain. With {@code gateway.cache.enabled=false} none of
 * these beans exist and requests go straight from rate limiting to the providers.
 */
@Configuration
@ConditionalOnProperty(prefix = "gateway.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig {

    /** Redis is only touched when the redis store is chosen, as with the rate limiter. */
    @Bean
    CacheStore cacheStore(CacheProperties properties, EmbeddingProperties embedding,
            ObjectProvider<StringRedisTemplate> redis, ObjectMapper json) {
        return switch (properties.store()) {
            case IN_MEMORY -> new InMemoryCacheStore(Clock.systemUTC());
            case REDIS -> new RedisCacheStore(redis.getObject(), json, embedding.dimensions());
        };
    }

    /** No embedder (semantic layer off): only the exact layer runs. */
    @Bean
    CacheInterceptor cacheInterceptor(CacheStore store, CacheProperties properties, ObjectProvider<Embedder> embedder) {
        return new CacheInterceptor(store, properties, embedder.getIfAvailable(), Clock.systemUTC());
    }
}
