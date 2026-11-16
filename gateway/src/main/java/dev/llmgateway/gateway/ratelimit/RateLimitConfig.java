package dev.llmgateway.gateway.ratelimit;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Wires rate limiting into the interceptor chain. With {@code gateway.rate-limit.enabled=false}
 * none of these beans exist and the chain is exactly as in week 1.
 */
@Configuration
@ConditionalOnProperty(prefix = "gateway.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    @Bean
    RateLimiter rateLimiter() {
        // Redis backend arrives in the next step; until then every instance counts locally.
        return new InMemoryRateLimiter(Clock.systemUTC());
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
