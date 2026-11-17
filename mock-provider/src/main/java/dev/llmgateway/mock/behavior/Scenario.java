package dev.llmgateway.mock.behavior;

/**
 * What the mock will do with one request.
 *
 * @param kind             the outcome
 * @param httpStatus       status to return when {@code kind == HTTP_ERROR}
 * @param failAfterChunks  number of content chunks sent before the connection drops,
 *                         when {@code kind == FAIL_MID_STREAM}
 * @param extraLatencyMs   added to the configured latency (the slow tail); 0 normally
 */
public record Scenario(Kind kind, int httpStatus, int failAfterChunks, long extraLatencyMs) {

    public enum Kind { SUCCESS, HTTP_ERROR, FAIL_MID_STREAM }

    public static Scenario success() {
        return new Scenario(Kind.SUCCESS, 200, -1, 0);
    }

    /** A successful answer that arrives {@code extraLatencyMs} later than usual. */
    public static Scenario slow(long extraLatencyMs) {
        return new Scenario(Kind.SUCCESS, 200, -1, extraLatencyMs);
    }

    public static Scenario httpError(int status) {
        return new Scenario(Kind.HTTP_ERROR, status, -1, 0);
    }

    public static Scenario failMidStream(int afterChunks) {
        return new Scenario(Kind.FAIL_MID_STREAM, 200, afterChunks, 0);
    }

    /** True if the stream should drop instead of sending content chunk number {@code index}. */
    public boolean shouldDropBefore(int index) {
        return kind == Kind.FAIL_MID_STREAM && index >= failAfterChunks;
    }
}
