package dev.llmgateway.mock.behavior;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Knobs that control how the mock behaves when a request does not force a scenario.
 * Each one can be set with an environment variable, for example
 * {@code MOCK_LATENCY_MS=50} or {@code MOCK_ERROR_RATE=0.1}.
 *
 * @param latencyMs            delay before the first byte of every response
 * @param errorRate            probability (0..1) of answering with HTTP 500
 * @param midStreamFailureRate probability (0..1) that a stream drops halfway
 * @param chunkCount           number of content chunks in a response
 * @param chunkDelayMs         delay between streamed chunks
 * @param slowRate             probability (0..1) that a request is slow (a latency tail)
 * @param slowLatencyMs        extra delay added to a slow request
 * @param flapPeriodMs         flapping cycle length; 0 turns flapping off
 * @param flapDownMs           at the start of every cycle, how long the mock answers 503
 */
@ConfigurationProperties(prefix = "mock")
public record MockBehaviorProperties(
        @DefaultValue("0") long latencyMs,
        @DefaultValue("0.0") double errorRate,
        @DefaultValue("0.0") double midStreamFailureRate,
        @DefaultValue("5") int chunkCount,
        @DefaultValue("10") long chunkDelayMs,
        @DefaultValue("0.0") double slowRate,
        @DefaultValue("2000") long slowLatencyMs,
        @DefaultValue("0") long flapPeriodMs,
        @DefaultValue("0") long flapDownMs) {

    /** The week-1 knobs only; slow tail and flapping off. */
    public static MockBehaviorProperties basic(long latencyMs, double errorRate, double midStreamFailureRate,
                                               int chunkCount, long chunkDelayMs) {
        return new MockBehaviorProperties(latencyMs, errorRate, midStreamFailureRate, chunkCount, chunkDelayMs,
                0.0, 2000, 0, 0);
    }
}
