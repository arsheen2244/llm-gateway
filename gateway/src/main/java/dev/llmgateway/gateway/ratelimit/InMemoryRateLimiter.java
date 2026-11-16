package dev.llmgateway.gateway.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sliding-window-log limiter in this JVM's memory.
 *
 * <p>Two uses: the <b>baseline</b> for the drift measurement (each gateway instance counts
 * on its own, so N instances admit up to N times the limit), and the <b>fallback</b> when
 * Redis is unreachable. It also serves as the readable reference for the semantics the
 * Redis Lua script implements.
 *
 * <p>Each client has its own window and its own lock, so clients never wait on each other.
 * A {@link ReentrantLock} rather than {@code synchronized}: on JDK 21 a virtual thread
 * blocked inside {@code synchronized} pins its carrier thread (design note 2).
 */
public class InMemoryRateLimiter implements RateLimiter {

    private final Clock clock;
    private final Map<String, ClientWindow> windows = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public RateLimitDecision tryAcquire(String clientId, Limits limits, long estimatedTokens) {
        ClientWindow window = windows.computeIfAbsent(clientId, id -> new ClientWindow());
        window.lock.lock();
        try {
            long now = clock.millis();
            window.expire(now, limits.window().toMillis());
            long remainingRequests = limits.maxRequests() - window.entries.size();
            long remainingTokens = limits.maxTokens() - window.tokenSum;

            if (estimatedTokens > limits.maxTokens()) {
                return RateLimitDecision.tooLarge(remainingRequests, remainingTokens);
            }
            if (remainingRequests >= 1 && remainingTokens >= estimatedTokens) {
                Entry entry = new Entry(UUID.randomUUID().toString(), now, estimatedTokens);
                window.add(entry);
                Reservation reservation = new Reservation(clientId, entry.id, estimatedTokens, limits.window());
                return RateLimitDecision.allowed(reservation, remainingRequests - 1, remainingTokens - estimatedTokens);
            }
            return RateLimitDecision.rejected(remainingRequests, remainingTokens,
                    window.retryAfter(now, limits, estimatedTokens));
        } finally {
            window.lock.unlock();
        }
    }

    @Override
    public boolean reconcile(Reservation reservation, long actualTokens) {
        ClientWindow window = windows.get(reservation.clientId());
        if (window == null) {
            return false;
        }
        window.lock.lock();
        try {
            window.expire(clock.millis(), reservation.window().toMillis());
            Entry entry = window.byId.get(reservation.id());
            if (entry == null) {
                return false; // already left the window: it no longer counts
            }
            long actual = Math.max(0, actualTokens);
            window.tokenSum += actual - entry.tokens;
            entry.tokens = actual;
            return true;
        } finally {
            window.lock.unlock();
        }
    }

    /** One admitted request. Tokens change on reconcile. */
    private static final class Entry {
        final String id;
        final long admittedAt;
        long tokens;

        Entry(String id, long admittedAt, long tokens) {
            this.id = id;
            this.admittedAt = admittedAt;
            this.tokens = tokens;
        }
    }

    /** One client's admitted requests, oldest first, plus a running token total. */
    private static final class ClientWindow {
        final ReentrantLock lock = new ReentrantLock();
        final Deque<Entry> entries = new ArrayDeque<>();
        final Map<String, Entry> byId = new HashMap<>();
        long tokenSum;

        void add(Entry entry) {
            entries.addLast(entry);
            byId.put(entry.id, entry);
            tokenSum += entry.tokens;
        }

        /** Drops entries admitted at or before {@code now - window}. */
        void expire(long now, long windowMs) {
            while (!entries.isEmpty() && entries.peekFirst().admittedAt <= now - windowMs) {
                Entry old = entries.removeFirst();
                byId.remove(old.id);
                tokenSum -= old.tokens;
            }
        }

        /**
         * Walks entries oldest-first, pretending each has expired, until the request
         * would fit both limits. The answer is when that entry actually expires.
         */
        Duration retryAfter(long now, Limits limits, long estimatedTokens) {
            long windowMs = limits.window().toMillis();
            long count = entries.size();
            long tokens = tokenSum;
            Iterator<Entry> oldestFirst = entries.iterator();
            while (oldestFirst.hasNext()) {
                Entry entry = oldestFirst.next();
                count--;
                tokens -= entry.tokens;
                if (count + 1 <= limits.maxRequests() && tokens + estimatedTokens <= limits.maxTokens()) {
                    return Duration.ofMillis(entry.admittedAt + windowMs - now);
                }
            }
            // Unreachable when the estimate fits the limit: an empty window always fits.
            return Duration.ofMillis(windowMs);
        }
    }
}
