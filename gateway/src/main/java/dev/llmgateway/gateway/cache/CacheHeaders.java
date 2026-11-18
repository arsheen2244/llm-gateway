package dev.llmgateway.gateway.cache;

/** The {@code X-Cache} response header and its values. */
public final class CacheHeaders {

    public static final String X_CACHE = "X-Cache";
    /** On semantic hits: the cosine similarity of the matched prompt (0 to 1, 4 decimals). */
    public static final String X_CACHE_SIMILARITY = "X-Cache-Similarity";

    /** Served from the exact-match layer. */
    public static final String EXACT = "exact";
    /** Served from the semantic layer. */
    public static final String SEMANTIC = "semantic";
    /** The cache was checked and had nothing; the provider answered. */
    public static final String MISS = "miss";
    /** The cache was not used: the client opted out, or the request isn't eligible. */
    public static final String BYPASS = "bypass";

    private CacheHeaders() {
    }

    /** True when the answer came from the cache (no provider tokens were spent). */
    public static boolean isHit(String value) {
        return EXACT.equals(value) || SEMANTIC.equals(value);
    }
}
