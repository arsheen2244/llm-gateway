package dev.llmgateway.gateway.pipeline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Headers that pipeline steps want on the HTTP response (for example rate-limit
 * headers). The pipeline knows nothing about HTTP, so steps write here and the HTTP
 * layer copies these onto the real response when it is sent.
 *
 * <p>One instance per request, used by that request's thread only.
 */
public final class ResponseHeaders {

    private final Map<String, String> values = new LinkedHashMap<>();

    public void set(String name, String value) {
        values.put(name, value);
    }

    public void setAll(Map<String, String> headers) {
        values.putAll(headers);
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(values);
    }
}
