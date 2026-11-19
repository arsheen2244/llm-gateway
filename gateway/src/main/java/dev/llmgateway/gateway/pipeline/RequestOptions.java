package dev.llmgateway.gateway.pipeline;

import java.util.Locale;

/**
 * Per-request choices the client made through HTTP headers, carried into the (HTTP-free)
 * pipeline.
 *
 * @param cacheBypass the client sent {@code Cache-Control: no-cache} (or {@code no-store}):
 *                    don't answer from the cache
 */
public record RequestOptions(boolean cacheBypass) {

    public static final RequestOptions DEFAULTS = new RequestOptions(false);

    public static RequestOptions fromCacheControl(String cacheControl) {
        if (cacheControl == null) {
            return DEFAULTS;
        }
        String value = cacheControl.toLowerCase(Locale.ROOT);
        return new RequestOptions(value.contains("no-cache") || value.contains("no-store"));
    }
}
