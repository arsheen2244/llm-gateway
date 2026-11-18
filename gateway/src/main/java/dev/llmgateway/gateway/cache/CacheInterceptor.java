package dev.llmgateway.gateway.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.llmgateway.gateway.api.dto.ChatCompletionChunk;
import dev.llmgateway.gateway.api.dto.ChatCompletionResponse;
import dev.llmgateway.gateway.api.dto.ChatMessage;
import dev.llmgateway.gateway.api.dto.Choice;
import dev.llmgateway.gateway.api.dto.ChunkChoice;
import dev.llmgateway.gateway.api.dto.Delta;
import dev.llmgateway.gateway.api.dto.Usage;
import dev.llmgateway.gateway.pipeline.ChatContext;
import dev.llmgateway.gateway.pipeline.ChatInterceptor;
import dev.llmgateway.gateway.provider.ChunkSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

/**
 * The response cache, in the interceptor chain right after rate limiting (design notes 32-35).
 *
 * <ul>
 *   <li>Not eligible, or the client sent {@code Cache-Control: no-cache}:
 *       {@code X-Cache: bypass}, and the call goes through untouched.</li>
 *   <li>Exact hit: answered from the cache ({@code X-Cache: exact}); no provider is called.
 *       Streams get the cached answer replayed as SSE.</li>
 *   <li>Otherwise, for single-turn requests, the semantic layer: embed the user message and
 *       look for a cached prompt in the same scope with similarity at or above the threshold
 *       ({@code X-Cache: semantic}, plus {@code X-Cache-Similarity}).</li>
 *   <li>Miss ({@code X-Cache: miss}): the call goes through, and a completed answer is stored
 *       in both layers. Failed calls and broken streams are never stored.</li>
 * </ul>
 * The cache must never fail a request: errors from the store or the embedding service are
 * logged and treated as misses.
 */
public class CacheInterceptor implements ChatInterceptor, Ordered {

    /** After rate limiting (100): a hit still counts as a request against the client's limit. */
    public static final int ORDER = 200;

    private static final Logger log = LoggerFactory.getLogger(CacheInterceptor.class);
    private static final Pattern AFTER_WHITESPACE = Pattern.compile("(?<=\\s)");

    private final CacheStore store;
    private final CacheEligibility eligibility;
    private final Duration ttl;
    private final Embedder embedder; // null: semantic layer off
    private final boolean semanticEnabled;
    private final double threshold;
    private final Clock clock;

    /** Exact layer only. */
    public CacheInterceptor(CacheStore store, CacheProperties properties, Clock clock) {
        this(store, properties, null, clock);
    }

    public CacheInterceptor(CacheStore store, CacheProperties properties, Embedder embedder, Clock clock) {
        this.store = store;
        this.eligibility = new CacheEligibility(properties.maxTemperature());
        this.ttl = properties.ttl();
        this.embedder = embedder;
        this.semanticEnabled = properties.semanticEnabled() && embedder != null;
        this.threshold = properties.similarityThreshold();
        this.clock = clock;
    }

    /**
     * What the lookup found, plus what a miss needs to store its answer: the exact key, and
     * the scope and embedding when the semantic layer applies (so the prompt is embedded once).
     */
    private record Lookup(Optional<CachedAnswer> hit, String exactKey, String scope, float[] vector) {

        static Lookup hit(CachedAnswer answer) {
            return new Lookup(Optional.of(answer), null, null, null);
        }

        static Lookup miss(String exactKey, String scope, float[] vector) {
            return new Lookup(Optional.empty(), exactKey, scope, vector);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatCompletionResponse complete(ChatContext context, Chain chain) {
        if (!usable(context)) {
            return chain.complete(context);
        }
        Lookup lookup = lookup(context);
        if (lookup.hit().isPresent()) {
            return toResponse(lookup.hit().get());
        }
        ChatCompletionResponse response = chain.complete(context);
        fromResponse(response).ifPresent(answer -> save(lookup, answer));
        return response;
    }

    @Override
    public void stream(ChatContext context, ChunkSink sink, Chain chain) {
        if (!usable(context)) {
            chain.stream(context, sink);
            return;
        }
        Lookup lookup = lookup(context);
        if (lookup.hit().isPresent()) {
            replay(lookup.hit().get(), sink);
            return;
        }
        StreamCollector collected = new StreamCollector();
        chain.stream(context, chunk -> {
            collected.add(chunk);
            sink.accept(chunk);
        });
        // Only reached if the stream completed; a broken one threw and is never stored.
        collected.answer().ifPresent(answer -> save(lookup, answer));
    }

    private boolean usable(ChatContext context) {
        boolean usable = !context.options().cacheBypass() && eligibility.exactEligible(context.request());
        if (!usable) {
            context.responseHeaders().set(CacheHeaders.X_CACHE, CacheHeaders.BYPASS);
        }
        return usable;
    }

    /** Exact layer first (cheap: one key lookup), then the semantic layer. Sets X-Cache. */
    private Lookup lookup(ChatContext context) {
        String exactKey = CacheKeys.exact(context.client().id(), context.request());
        Optional<CachedAnswer> exact = getExact(exactKey);
        if (exact.isPresent()) {
            context.responseHeaders().set(CacheHeaders.X_CACHE, CacheHeaders.EXACT);
            return Lookup.hit(exact.get());
        }
        context.responseHeaders().set(CacheHeaders.X_CACHE, CacheHeaders.MISS);
        if (!semanticEnabled || !eligibility.semanticEligible(context.request())) {
            return Lookup.miss(exactKey, null, null);
        }
        String scope = CacheKeys.scope(context.client().id(), context.request());
        float[] vector = embed(CacheKeys.semanticText(context.request()));
        if (vector == null) {
            return Lookup.miss(exactKey, null, null);
        }
        Optional<SemanticMatch> match = findSimilar(scope, vector);
        if (match.isPresent()) {
            context.responseHeaders().set(CacheHeaders.X_CACHE, CacheHeaders.SEMANTIC);
            context.responseHeaders().set(CacheHeaders.X_CACHE_SIMILARITY,
                    String.format(Locale.ROOT, "%.4f", match.get().similarity()));
            return Lookup.hit(match.get().answer());
        }
        return Lookup.miss(exactKey, scope, vector);
    }

    private Optional<CachedAnswer> getExact(String key) {
        try {
            return store.getExact(key);
        } catch (RuntimeException e) {
            log.warn("Cache lookup failed; treating as a miss: {}", e.toString());
            return Optional.empty();
        }
    }

    /** @return the embedding, or null if the service failed (the semantic layer is skipped) */
    private float[] embed(String text) {
        try {
            return embedder.embed(text);
        } catch (RuntimeException e) {
            log.warn("Embedding failed; skipping the semantic cache: {}", e.toString());
            return null;
        }
    }

    private Optional<SemanticMatch> findSimilar(String scope, float[] vector) {
        try {
            return store.findSimilar(scope, vector, threshold);
        } catch (RuntimeException e) {
            log.warn("Semantic cache lookup failed; treating as a miss: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Both layers; the semantic one only when this request was embedded. */
    private void save(Lookup lookup, CachedAnswer answer) {
        try {
            store.putExact(lookup.exactKey(), answer, ttl);
            if (lookup.vector() != null) {
                store.putSemantic(lookup.scope(), lookup.vector(), answer, ttl);
            }
        } catch (RuntimeException e) {
            log.warn("Cache store failed; answer not cached: {}", e.toString());
        }
    }

    /** Only complete answers are worth caching. */
    static Optional<CachedAnswer> fromResponse(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return Optional.empty();
        }
        Choice choice = response.choices().get(0);
        if (choice.message() == null || choice.message().content() == null || !cacheable(choice.finishReason())) {
            return Optional.empty();
        }
        Usage usage = response.usage();
        return Optional.of(new CachedAnswer(choice.message().content(), choice.finishReason(), response.model(),
                usage == null ? 0 : usage.promptTokens(), usage == null ? 0 : usage.completionTokens()));
    }

    /** "stop" (finished) and "length" (cut at max_tokens, which is part of the scope) are deterministic. */
    static boolean cacheable(String finishReason) {
        return "stop".equals(finishReason) || "length".equals(finishReason);
    }

    private ChatCompletionResponse toResponse(CachedAnswer answer) {
        return new ChatCompletionResponse(newId(), "chat.completion", clock.instant().getEpochSecond(),
                answer.model(), List.of(new Choice(0, new ChatMessage("assistant", answer.content()),
                answer.finishReason())), Usage.of(answer.promptTokens(), answer.completionTokens()));
    }

    /**
     * Replays a cached answer the way a provider streams: a role chunk, the text in word-sized
     * pieces, a finish chunk, then the usage chunk (forwarded only if the client asked for it).
     */
    private void replay(CachedAnswer answer, ChunkSink sink) {
        String id = newId();
        long created = clock.instant().getEpochSecond();
        sink.accept(chunk(id, created, answer.model(), new Delta("assistant", ""), null));
        for (String piece : AFTER_WHITESPACE.split(answer.content())) {
            if (!piece.isEmpty()) {
                sink.accept(chunk(id, created, answer.model(), new Delta(null, piece), null));
            }
        }
        sink.accept(chunk(id, created, answer.model(), new Delta(null, null), answer.finishReason()));
        sink.accept(new ChatCompletionChunk(id, "chat.completion.chunk", created, answer.model(), List.of(),
                Usage.of(answer.promptTokens(), answer.completionTokens())));
    }

    private static ChatCompletionChunk chunk(String id, long created, String model, Delta delta, String finishReason) {
        return new ChatCompletionChunk(id, "chat.completion.chunk", created, model,
                List.of(new ChunkChoice(0, delta, finishReason)), null);
    }

    private static String newId() {
        return "chatcmpl-cache-" + UUID.randomUUID();
    }

    /** Rebuilds the full answer from a stream as it passes through. */
    static final class StreamCollector {
        private final List<String> pieces = new ArrayList<>();
        private String model;
        private String finishReason;
        private Usage usage;

        void add(ChatCompletionChunk chunk) {
            if (chunk.model() != null) {
                model = chunk.model();
            }
            if (chunk.usage() != null) {
                usage = chunk.usage();
            }
            if (chunk.choices() == null) {
                return;
            }
            for (ChunkChoice choice : chunk.choices()) {
                if (choice.delta() != null && choice.delta().content() != null) {
                    pieces.add(choice.delta().content());
                }
                if (choice.finishReason() != null) {
                    finishReason = choice.finishReason();
                }
            }
        }

        Optional<CachedAnswer> answer() {
            if (!cacheable(finishReason)) {
                return Optional.empty();
            }
            return Optional.of(new CachedAnswer(String.join("", pieces), finishReason, model,
                    usage == null ? 0 : usage.promptTokens(), usage == null ? 0 : usage.completionTokens()));
        }
    }
}
