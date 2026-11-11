package dev.llmgateway.mock.behavior;

/**
 * What the mock will do with one request.
 *
 * @param kind             the outcome
 * @param httpStatus       status to return when {@code kind == HTTP_ERROR}
 * @param failAfterChunks  number of content chunks sent before the connection drops,
 *                         when {@code kind == FAIL_MID_STREAM}
 */
public record Scenario(Kind kind, int httpStatus, int failAfterChunks) {

    public enum Kind { SUCCESS, HTTP_ERROR, FAIL_MID_STREAM }

    public static Scenario success() {
        return new Scenario(Kind.SUCCESS, 200, -1);
    }

    public static Scenario httpError(int status) {
        return new Scenario(Kind.HTTP_ERROR, status, -1);
    }

    public static Scenario failMidStream(int afterChunks) {
        return new Scenario(Kind.FAIL_MID_STREAM, 200, afterChunks);
    }

    /** True if the stream should drop instead of sending content chunk number {@code index}. */
    public boolean shouldDropBefore(int index) {
        return kind == Kind.FAIL_MID_STREAM && index >= failAfterChunks;
    }
}
