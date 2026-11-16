package dev.llmgateway.gateway.ratelimit;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires rate limiting into the interceptor chain. With {@code gateway.rate-limit.enabled=false}
 * none of these beans exist and the chain is exactly as in week 1.
 */
@Configuration
@ConditionalOnProperty(prefix = "gateway.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    /**
     * {@code redis}: windows shared by all instances. {@code in-memory}: each instance counts
     * on its own (the baseline). The Redis connection is only requested for the redis backend,
     * so the in-memory backend never touches Redis. The Redis limiter is always wrapped in the
     * local fallback for when Redis is unreachable (design note 22).
     */
    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties, ObjectProvider<StringRedisTemplate> redis) {
        Clock clock = Clock.systemUTC();
        return switch (properties.backend()) {
            case IN_MEMORY -> new InMemoryRateLimiter(clock);
            case REDIS -> new FallbackRateLimiter(new RedisRateLimiter(redis.getObject()),
                    new InMemoryRateLimiter(clock), properties.instances(), properties.redisRetryInterval(), clock);
        };
    }

    @Bean
    TokenEstimator tokenEstimator(RateLimitProperties properties) {
        return new TokenEstimator(properties.defaultCompletionTokens());
    }

    /** First in the chain: a refused request should cost nothing else. */
    @Bean
    @Order(100)
    RateLimitInterceptor rateLimitInterceptor(RateLimiter limiter, TokenEstimator estimator,
                                              RateLimitProperties properties) {
        return new RateLimitInterceptor(limiter, estimator, properties);
    }
}
