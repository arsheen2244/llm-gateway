package dev.llmgateway.gateway.support;

import java.net.URI;

import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Finds a Redis for tests, in this order:
 * <ol>
 *   <li>{@code REDIS_URL} (e.g. {@code redis://localhost:6379}): any Redis you already run.
 *       Tests use unique keys per test and never flush, but use a Redis with nothing
 *       important in it.</li>
 *   <li>A throwaway Testcontainers Redis, if Docker is available (it is in CI).</li>
 *   <li>Neither: the calling test is <b>skipped</b> (not failed), so local builds without
 *       Docker still pass.</li>
 * </ol>
 */
public final class RedisTestSupport {

    /** Redis 8 has vector search built in (the semantic cache, design note 35). */
    private static final String IMAGE = "redis:8.2";
    private static GenericContainer<?> container;
    private static String url;

    private RedisTestSupport() {
    }

    /** A Redis URL, or skips the current test if no Redis can be found. */
    public static synchronized String url() {
        if (url != null) {
            return url;
        }
        String configured = System.getenv("REDIS_URL");
        if (configured != null && !configured.isBlank()) {
            url = configured;
            return url;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Redis for this test: set REDIS_URL or make Docker available (CI has it)");
        container = new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(6379);
        container.start(); // stopped automatically when the JVM exits
        url = "redis://" + container.getHost() + ":" + container.getMappedPort(6379);
        return url;
    }

    /** A new, independent connection: like a separate gateway instance would have. */
    public static StringRedisTemplate newTemplate() {
        URI uri = URI.create(url());
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(uri.getHost(), uri.getPort()));
        factory.afterPropertiesSet();
        factory.start();
        return new StringRedisTemplate(factory);
    }
}
