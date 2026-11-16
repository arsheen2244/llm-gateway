package dev.llmgateway.gateway.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Sliding-window-log limiter whose windows live in Redis, shared by every gateway
 * instance (design note 20).
 *
 * <p>All the logic is in two Lua scripts ({@code ratelimit/acquire.lua} and
 * {@code ratelimit/reconcile.lua}). Redis runs a script as one atomic step, so
 * "check the limits, then record the request" cannot interleave with another instance
 * doing the same. This class only builds the keys and arguments and turns the
 * scripts' replies into {@link RateLimitDecision}s.
 *
 * <p>Spring sends scripts with {@code EVALSHA} (by hash, so the script body is not
 * re-sent every call) and falls back to {@code EVAL} if Redis has not seen the script yet.
 */
public class RedisRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ACQUIRE =
            RedisScript.of(new ClassPathResource("ratelimit/acquire.lua"), List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RECONCILE =
            RedisScript.of(new ClassPathResource("ratelimit/reconcile.lua"), List.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitDecision tryAcquire(String clientId, Limits limits, long estimatedTokens) {
        String reservationId = UUID.randomUUID().toString();
        List<Long> reply = run(ACQUIRE, 4, keys(clientId),
                limits.window().toMillis(), limits.maxRequests(), limits.maxTokens(), estimatedTokens, reservationId);
        long allowed = reply.get(0);
        long remainingRequests = reply.get(1);
        long remainingTokens = reply.get(2);
        long retryAfterMs = reply.get(3);

        if (allowed == 1) {
            Reservation reservation = new Reservation(clientId, reservationId, estimatedTokens, limits.window());
            return RateLimitDecision.allowed(reservation, remainingRequests, remainingTokens);
        }
        if (retryAfterMs == -1) {
            return RateLimitDecision.tooLarge(remainingRequests, remainingTokens);
        }
        return RateLimitDecision.rejected(remainingRequests, remainingTokens, Duration.ofMillis(retryAfterMs));
    }

    @Override
    public boolean reconcile(Reservation reservation, long actualTokens) {
        List<Long> reply = run(RECONCILE, 2, keys(reservation.clientId()),
                reservation.window().toMillis(), reservation.id(), Math.max(0, actualTokens));
        return reply.get(0) == 1;
    }

    /**
     * The three keys of one client's window. The {@code {clientId}} part is a Redis
     * Cluster "hash tag": only the text inside braces decides which node owns a key, so
     * all three keys of a client land on the same node, which a multi-key script requires.
     * Client ids are validated to {@code [A-Za-z0-9._-]}, so they cannot contain braces.
     */
    static List<String> keys(String clientId) {
        String base = "rl:{" + clientId + "}:";
        return List.of(base + "log", base + "tokens", base + "sum");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Long> run(RedisScript<List> script, int expectedSize, List<String> keys, Object... args) {
        String[] argv = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            argv[i] = String.valueOf(args[i]);
        }
        List reply = redis.execute(script, keys, (Object[]) argv);
        // Fail with a clear message if a script returns the wrong shape; this is what
        // you will see while writing the script, so it should say what was expected.
        if (reply == null || reply.size() != expectedSize) {
            throw new IllegalStateException("Rate-limit script returned " + reply
                    + ", expected a list of " + expectedSize + " integers");
        }
        for (Object value : reply) {
            if (!(value instanceof Long)) {
                throw new IllegalStateException("Rate-limit script returned " + reply
                        + ": every element must be an integer (Lua numbers are truncated to integers;"
                        + " strings and booleans are not)");
            }
        }
        return (List<Long>) reply;
    }
}
